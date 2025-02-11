/*
 * Copyright 2003-2008 Terracotta, Inc.
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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Dummy webclient to test out Http no response exception
 * This is to be used only in test framework since it sends
 * along cookies (got from first response) every time regardless
 * expires, domain, path properties 
 *
 * @author hhuynh
 */
public class SimpleWebClient {
  private Map          cookies = new HashMap();

  public String getResponseAsString(URL url) throws IOException {
    HttpURLConnection http = (HttpURLConnection) url.openConnection();
    http.setRequestProperty("Cookie", getCookiesAsString());
    http.connect();
    if (http.getResponseCode() != HttpURLConnection.HTTP_OK) {      
      throw new IOException("Response code is not OK: " + http.getResponseMessage() + "\nRequest: " + url);
    }
    extractCookies(http);
    BufferedReader in = new BufferedReader(new InputStreamReader(http.getInputStream()));
    StringBuffer buffer = new StringBuffer(100);
    String line;
    try {
      while ((line = in.readLine()) != null) {
        buffer.append(line).append("\n");
      }
    } finally {
      try {
        in.close();
      } catch (Exception ignored) { // nop
      }
    }

    return buffer.toString().trim();
  }
  
  public int getResponseAsInt(URL url) throws IOException {
    String response = getResponseAsString(url);
    return Integer.parseInt(response);
  }

  public void setCookies(Map cookies) {
    this.cookies = cookies;
    System.out.println(cookies);
  }
  
  public Map getCookies() {
    return cookies;
  }
  
  private String getCookiesAsString() {
    StringBuffer cookiesString = new StringBuffer(100);
    for (Iterator it = cookies.entrySet().iterator(); it.hasNext();) {
      Map.Entry e = (Map.Entry) it.next();
      cookiesString.append(e.getKey().toString()).append("=").append(e.getValue().toString());
      if (it.hasNext()) {
        cookiesString.append("; ");
      }
    }
    return cookiesString.toString();
  }

  private void extractCookies(URLConnection urlConnect) {
    Map headerFields = urlConnect.getHeaderFields();
    List cookieList = (List) headerFields.get("Set-Cookie");
    if (cookieList != null) {
      for (Iterator it = cookieList.iterator(); it.hasNext();) {
        String cookie = (String) it.next();
        // parse first name=value, ignore the rest
        cookie = cookie.substring(0, cookie.indexOf(";"));
        String[] pair = cookie.split("=", 2);
        cookies.put(pair[0], pair[1]);
      }
    }
  }
}
