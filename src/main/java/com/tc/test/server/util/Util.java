/*
 * Copyright Terracotta, Inc.
 * Copyright Super iPaaS Integration LLC, an IBM Company 2024
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tc.test.server.util;

import com.tc.util.runtime.Os;

import java.net.URL;
import java.net.URLDecoder;
import java.security.CodeSource;
import java.security.ProtectionDomain;

public class Util {
  public static String jarFor(Class c) {
    ProtectionDomain protectionDomain = c.getProtectionDomain();
    CodeSource codeSource = protectionDomain.getCodeSource();
    URL url = codeSource.getLocation();
    String path = url.getPath();
    if (Os.isWindows() && path.startsWith("/")) {
      path = path.substring(1);
    }
    return URLDecoder.decode(path);
  }

}