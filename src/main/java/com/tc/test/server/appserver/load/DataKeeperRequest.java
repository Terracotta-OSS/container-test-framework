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
package com.tc.test.server.appserver.load;

import org.apache.commons.httpclient.HttpClient;

import com.tc.util.Assert;

import java.net.URL;

public class DataKeeperRequest implements Request {

  private static final int UNDEFINED = -1;
  private long             enterQueueTime;
  private long             exitQueueTime;
  private long             processCompletionTime;
  private final HttpClient client;
  private final int        appserverID;
  private final URL        url;

  public DataKeeperRequest(HttpClient client, int appserverID, URL url) {
    this.client = client;
    this.appserverID = appserverID;
    this.url = url;
    this.enterQueueTime = UNDEFINED;
    this.exitQueueTime = UNDEFINED;
    this.processCompletionTime = UNDEFINED;
  }

  public void setEnterQueueTime() {
    Assert.assertEquals(UNDEFINED, this.enterQueueTime);
    // this.enterQueueTime = System.nanoTime();
    this.enterQueueTime = System.currentTimeMillis();
  }

  public void setExitQueueTime() {
    Assert.assertEquals(UNDEFINED, this.exitQueueTime);
    // this.exitQueueTime = System.nanoTime();
    this.exitQueueTime = System.currentTimeMillis();
  }

  public void setProcessCompletionTime() {
    Assert.assertEquals(UNDEFINED, this.processCompletionTime);
    // this.processCompletionTime = System.nanoTime();
    this.processCompletionTime = System.currentTimeMillis();
  }

  public URL getUrl() {
    return this.url;
  }

  public long getEnterQueueTime() {
    return this.enterQueueTime;
  }

  public long getExitQueueTime() {
    return this.exitQueueTime;
  }

  public long getProcessCompletionTime() {
    return this.processCompletionTime;
  }

  public HttpClient getClient() {
    return this.client;
  }

  public int getAppserverID() {
    return this.appserverID;
  }

  public String toString() {
    return "client=" + this.client + " AppserverID=" + this.appserverID;
  }

  public String printData() {

    return this.enterQueueTime + "," + this.exitQueueTime + "," + this.processCompletionTime + this.appserverID + ","
           + this.client + "," + this.client.getState().getCookies()[0].toString();
  }
}
