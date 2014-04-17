/*
 * Copyright 2014 Twitter inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.twitter.storehaus

import com.twitter.conversions.time._
import com.twitter.storehaus.Store
import com.twitter.util._

/**
 * Read/Write retrying Store.
 * Make this available to all storehaus users.
 */
class RetryingRWStore[-K, V](store: Store[K, V], backoffs: Iterable[Duration])(implicit timer: Timer)
    extends Store[K, V] {

  private val padded_backoffs = backoffs ++ Seq(0.second)

  private def find[T](futures: Iterator[(Future[T], Duration)])(pred: T => Boolean): Future[T] = {
    if (!futures.hasNext) {
      Future.exception(new RuntimeException("RetryingRWStore: empty iterator in function find"))
    } else {
      val (next, delay) = futures.next()
      if (!futures.hasNext) {
        next
      } else {
        next.filter(pred).rescue {
          case e: Exception =>
            timer.doLater(delay)(()) flatMap { _ =>
              find(futures)(pred)
            }
        }
      }
    }
  }

  /**
   * First do a multiPut on the store. For the futures that fail, chain retry puts after delay.
   */
  override def multiPut[K1 <: K](kvs: Map[K1, Option[V]]): Map[K1, Future[Unit]] = {
    store.multiPut(kvs) map { case (k, future) =>
      val retryStream = (Iterator(future) ++ Iterator.continually { store.put((k, kvs(k)))})
          .zip(padded_backoffs.iterator)
      (k, find(retryStream) { t => true })
    }
  }

  /**
   * First do a multiGet on the store. For the futures that fail, chain retry gets after delay.
   */
  override def multiGet[K1 <: K](ks: Set[K1]): Map[K1, Future[Option[V]]] = {
    store.multiGet(ks) map { case (k, future) =>
      val retryStream = (Iterator(future) ++ Iterator.continually { store.get(k) })
          .zip(padded_backoffs.iterator)
      (k, find(retryStream) { t => true })
    }
  }
}
