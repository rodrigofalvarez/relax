/*
 * Copyright (C) 2016-2018 Craft+Resolve, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.craftandresolve.relax.exception;


public class HTTPCodeException extends Exception {

    private final int code;
    private final String shortText;

    public HTTPCodeException(int code, String shortText, String longText) {
        super(longText);
        this.code = code;
        this.shortText = shortText;
    }

    public int getCode() {
        return code;
    }

    public String getShortText() { return shortText; }
}
