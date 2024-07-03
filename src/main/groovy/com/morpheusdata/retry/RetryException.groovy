/*
 * Copyright 2024 Morpheus Data, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.morpheusdata.retry
/**
 * Thrown by retry utility.
 * @author Chris Taylor
 */
public class RetryException extends Exception {
  
  private final String code;

  public RetryException(String code) {
    super();
    this.code = code;
  }

  public RetryException(String message, Throwable cause, String code) {
    super(message, cause);
    this.code = code;
  }

  public RetryException(String message, String code) {
    super(message);
    this.code = code;
  }

  public RetryException(Throwable cause, String code) {
    super(cause);
    this.code = code;
  }

  public String getCode() {
    return this.code;
  }
}
