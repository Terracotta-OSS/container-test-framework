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

import org.apache.commons.io.FileUtils;

import com.tc.lcp.HeartBeatService;
import com.tc.test.TestConfigObject;
import com.tc.test.server.appserver.AppServerFactory;
import com.tc.test.server.appserver.AppServerInstallation;
import com.tc.text.Banner;
import com.tc.util.PortChooser;
import com.tc.util.concurrent.ThreadUtil;
import com.tc.util.runtime.Os;

import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;

public class AppServerUtil {

  private static final PortChooser      pc     = new PortChooser();
  private static final TestConfigObject config = TestConfigObject.getInstance();

  public static int getPort() {
    return pc.chooseRandomPort();
  }

  public static void waitForPort(int port, long waitTime) {
    final long timeout = System.currentTimeMillis() + waitTime;
    while (System.currentTimeMillis() < timeout) {
      if (pingPort(port)) { return; }
      ThreadUtil.reallySleep(1000);
    }

    throw new RuntimeException("Port " + port + " cannot be reached, timeout = " + waitTime);
  }

  public static void waitForPortToShutdown(int port, long waitTime) {
    final long timeout = System.currentTimeMillis() + waitTime;
    while (System.currentTimeMillis() < timeout) {
      if (!pingPort(port)) { return; }
      ThreadUtil.reallySleep(1000);
    }

    throw new RuntimeException("Port " + port + " cannot be reached, timeout = " + waitTime);
  }

  public static boolean pingPort(int port) {
    Socket s = null;
    try {
      s = new Socket("127.0.0.1", port);
      return true;
    } catch (IOException ioe) {
      return false;
    } finally {
      if (s != null) {
        try {
          s.close();
        } catch (IOException ioe) {
          // ignore
        }
      }
    }
  }

  public static String getFullName(String serverName, String majorVersion, String minorVersion) {
    return serverName.toLowerCase() + "-" + majorVersion.toLowerCase() + "." + minorVersion.toLowerCase();
  }

  public static boolean awaitShutdown(int timewait) {
    long start = System.currentTimeMillis();
    long timeout = timewait + start;
    boolean foundAlive = false;
    do {
      ThreadUtil.reallySleep(5000);
      foundAlive = HeartBeatService.anyAppServerAlive();
    } while (foundAlive && System.currentTimeMillis() < timeout);

    return foundAlive;
  }

  public static void shutdownAndArchive(File from, File to) {
    shutdown();
    archive(from, to);
  }

  public static void forceShutdownAndArchive(File from, File to) {
    System.out.println("Send kill signal to app servers...");
    HeartBeatService.sendKillSignalToChildren();
    ThreadUtil.reallySleep(1000);
    archive(from, to);
  }

  public static void shutdown() {
    awaitShutdown(2 * 60 * 1000);
    System.out.println("Send kill signal to app servers...");
    HeartBeatService.sendKillSignalToChildren();
  }

  public static File createSandbox(File tempDir) {
    File sandbox = null;
    if (Os.isWindows()) {
      sandbox = new File(config.cacheDir(), "sandbox");
    } else {
      sandbox = new File(tempDir, "sandbox");
    }

    try {
      if (sandbox.exists()) {
        if (sandbox.isDirectory()) {
          FileUtils.cleanDirectory(sandbox);
        } else {
          throw new RuntimeException(sandbox + " exists, but is not a directory");
        }
      }
    } catch (IOException e) {
      File prev = sandbox;
      sandbox = new File(sandbox.getAbsolutePath() + "-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()));
      Banner.warnBanner("Caught IOException setting up workDir as " + prev + ", using " + sandbox + " instead");
    }

    if (!sandbox.exists() && !sandbox.mkdirs()) { throw new RuntimeException("Failed to create sandbox: " + sandbox); }

    return sandbox;
  }

  public static AppServerInstallation createAppServerInstallation(AppServerFactory appServerFactory, File installDir,
                                                                  File sandbox) throws Exception {
    AppServerInstallation installation = null;
    String appserverHome = config.appserverHome();
    if (appserverHome != null && !appserverHome.trim().equals("")) {
      installation = appServerFactory.createInstallation(new File(appserverHome), sandbox, config.appServerInfo());
    } else {
      throw new AssertionError("No appserver found! You must define: " + TestConfigObject.APP_SERVER_HOME);
    }
    return installation;
  }

  public static void archive(File from, File to) {
    if (!from.equals(to)) {
      System.out.println("Copying files from " + from + " to " + to);
      try {
        com.tc.util.io.TCFileUtils.copyFile(from, to);
      } catch (IOException ioe) {
        Banner.warnBanner("IOException caught while copying workingDir files");
        ioe.printStackTrace();
      }
      System.out.println("Delete files in: " + from);
      try {
        FileUtils.forceDelete(from);
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }
}
