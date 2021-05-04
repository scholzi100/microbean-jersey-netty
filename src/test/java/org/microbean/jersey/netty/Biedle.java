/* -*- mode: Java; c-basic-offset: 2; indent-tabs-mode: nil; coding: utf-8-unix -*-
 *
 * Copyright © 2019 microBean™.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */
package org.microbean.jersey.netty;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.Path;

@Consumes("text/plain")
@Path("/biedle")
public class Biedle {

    @GET
    public String biedle() {
      // go over build-in Jersey cache (over 8192 bytes)
      return generateBiedleContent(25);
    }

    public static String generateBiedleContent(int borkenLines) {
      StringBuilder stringBuilder = new StringBuilder();
      //generate 8192 bytes of ordered nonsense
      for (int i = 0; i < 2048; i++) {
        stringBuilder.append("AbCdEfG\n");
      }

      //this should produce n broken line because of buffering inside CommittingOutputStream
      for (int i = 0; i < borkenLines; i++) {
        stringBuilder.append("1234567890\n");
      }
      return stringBuilder.toString();
    }
}
