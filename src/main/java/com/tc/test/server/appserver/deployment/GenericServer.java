/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.test.server.appserver.deployment;

import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.WebResponse;
import com.tc.test.AppServerInfo;
import com.tc.test.JMXUtils;
import com.tc.test.TestConfigObject;
import com.tc.test.server.ServerResult;
import com.tc.test.server.appserver.AppServer;
import com.tc.test.server.appserver.AppServerFactory;
import com.tc.test.server.appserver.AppServerInstallation;
import com.tc.test.server.appserver.StandardAppServerParameters;
import com.tc.test.server.util.AppServerUtil;
import com.tc.text.Banner;
import com.tc.util.runtime.Os;
import com.tc.util.runtime.ThreadDump;
import com.tc.util.runtime.Vm;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import javax.management.MBeanServerConnection;
import javax.management.remote.JMXConnector;

public class GenericServer extends AbstractStoppable implements WebApplicationServer {

  private static final String               SERVER                  = "server_";
  private static final boolean              GC_LOGGING              = true;
  public static final boolean               ENABLE_DEBUGGER         = Boolean.getBoolean(GenericServer.class.getName()
                                                                                         + ".ENABLE_DEBUGGER");
  public static boolean                     USE_DEFAULT_LICENSE_KEY = true;

  private final int                         jmxRemotePort;
  private final int                         rmiRegistryPort;
  private final AppServerFactory            factory;
  private AppServer                         server;
  private final StandardAppServerParameters parameters;
  private ServerResult                      result;
  private final AppServerInstallation       installation;
  private final File                        workingDir;
  private final String                      serverInstanceName;
  private final File                        tcConfigFile;
  private final int                         appId;

  public GenericServer(final TestConfigObject config, final AppServerFactory factory,
                       final AppServerInstallation installation, final File tcConfigFile, final int serverId,
                       final File tempDir) throws Exception {
    this.factory = factory;
    this.installation = installation;
    this.rmiRegistryPort = AppServerUtil.getPort();
    this.jmxRemotePort = AppServerUtil.getPort();
    this.serverInstanceName = SERVER + serverId;
    this.parameters = (StandardAppServerParameters) factory.createParameters(serverInstanceName);
    this.workingDir = new File(installation.sandboxDirectory(), serverInstanceName);
    this.tcConfigFile = tcConfigFile;

    if (!Vm.isIBM() && !(Os.isMac() && Vm.isJDK14())) {
      parameters.appendJvmArgs("-XX:+HeapDumpOnOutOfMemoryError");
    }

    appId = config.appServerId();
    // glassfish, jboss fails with these options on
    // see https://issues.jboss.org/browse/JBAS-7669 for jboss6

    if (!(appId == AppServerInfo.GLASSFISH || appId == AppServerInfo.JBOSS)) {
      parameters.appendSysProp("com.sun.management.jmxremote");
      parameters.appendSysProp("com.sun.management.jmxremote.authenticate", false);
      parameters.appendSysProp("com.sun.management.jmxremote.ssl", false);
      parameters.appendSysProp("com.sun.management.jmxremote.port", this.jmxRemotePort);
    }

    parameters.appendSysProp("com.tc.session.debug.sessions", true);
    parameters.appendSysProp("rmi.registry.port", this.rmiRegistryPort);

    String[] params = { "tc.classloader.writeToDisk", "tc.objectmanager.dumpHierarchy", "aspectwerkz.deployment.info",
        "aspectwerkz.details", "aspectwerkz.gen.closures", "aspectwerkz.dump.pattern", "aspectwerkz.dump.closures",
        "aspectwerkz.dump.factories", "aspectwerkz.aspectmodules" };
    for (String param : params) {
      if (Boolean.getBoolean(param)) {
        parameters.appendSysProp(param, true);
      }
    }

    enableDebug(serverId);

    // app server specific system props
    switch (appId) {
      case AppServerInfo.TOMCAT:
      case AppServerInfo.JBOSS:
        parameters.appendJvmArgs("-Djvmroute=" + serverInstanceName);
        break;
      case AppServerInfo.WEBSPHERE:
        parameters.appendSysProp("javax.management.builder.initial", "");
        break;
    }

    if (!Vm.isJRockit()) {
      parameters.appendJvmArgs("-XX:MaxPermSize=256m");
    }
    parameters.appendJvmArgs("-Xms128m -Xmx512m");

    if (Os.getOsName().startsWith("HP-UX")) {
      parameters.appendJvmArgs("-XX:ThreadStackSize=512k");
    }

    if (Os.isUnix() && new File("/dev/urandom").exists()) {
      // prevent hangs reading from /dev/random
      parameters.appendSysProp("java.security.egd", "file:/dev/./urandom");
    }

    // pass along product key path to app server if found
    // used for EE testing
    if (USE_DEFAULT_LICENSE_KEY) {
      String productKey = config.getProperty("com.tc.productkey.path");
      if (productKey != null) {
        System.out.println("XXX: adding license key to appserver: " + productKey);
        parameters.appendSysProp("com.tc.productkey.path", productKey);
      }
    }
  }

  @Override
  public StandardAppServerParameters getServerParameters() {
    return parameters;
  }

  @Override
  public int getPort() {
    if (result == null) { throw new IllegalStateException("Server has not started."); }
    return result.serverPort();
  }

  private void enableDebug(final int serverId) {
    if (GC_LOGGING && !Vm.isIBM() && appId != AppServerInfo.WEBSPHERE) {
      parameters.appendJvmArgs("-verbose:gc");

      if (!Vm.isJRockit()) {
        parameters.appendJvmArgs("-XX:+PrintGCDetails");
        parameters.appendJvmArgs("-XX:+PrintGCTimeStamps");
      }

      final String gcLogSwitch;
      if (Vm.isJRockit()) {
        gcLogSwitch = "verboselog";
      } else {
        gcLogSwitch = "loggc";
      }

      parameters.appendJvmArgs("-X"
                               + gcLogSwitch
                               + ":"
                               + new File(this.installation.sandboxDirectory(), serverInstanceName + "-gc.log")
                                   .getAbsolutePath());
    }

    if (ENABLE_DEBUGGER) {
      int debugPort = 8000 + serverId;
      if (appId == AppServerInfo.WEBSPHERE) {
        parameters.appendJvmArgs("-Xdebug -Xnoagent -Xrunjdwp:transport=dt_socket,server=y,suspend=y,address="
                                 + debugPort + " -Djava.compiler=NONE");
      } else {
        parameters.appendJvmArgs("-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=" + debugPort);
      }
      parameters.appendSysProp("aspectwerkz.transform.verbose", true);
      parameters.appendSysProp("aspectwerkz.transform.details", true);
      Banner.warnBanner("Waiting for debugger to connect on port " + debugPort);
    }
  }

  @Override
  public MBeanServerConnection getMBeanServerConnection() throws Exception {
    JMXConnector jmxConnectorProxy = JMXUtils.getJMXConnector("localhost", this.jmxRemotePort);
    return jmxConnectorProxy.getMBeanServerConnection();
  }

  @Override
  public WebApplicationServer addWarDeployment(final Deployment warDeployment, final String context) {
    parameters.addDeployment(context, warDeployment);
    return this;
  }

  @Override
  public WebApplicationServer addEarDeployment(final Deployment earDeployment) {
    parameters.addDeployment("", earDeployment);
    return this;
  }

  @Override
  protected void doStart() throws Exception {
    try {
      result = getAppServer().start(parameters);
    } catch (Exception e) {
      dumpThreadsAndRethrow(e);
    }
  }

  private void dumpThreadsAndRethrow(final Exception e) throws Exception {
    try {
      ThreadDump.dumpAllJavaProcesses(3, 1000);
    } catch (Throwable t) {
      t.printStackTrace();
    }

    throw e;
  }

  @Override
  protected void doStop() throws Exception {
    try {
      server.stop(parameters);
    } catch (Exception e) {
      dumpThreadsAndRethrow(e);
    }
  }

  /**
   * url: /<CONTEXT>/<MAPPING>?params=etc
   */
  @Override
  public WebResponse ping(final String url, final WebClient wc) throws IOException {
    wc.getOptions().setTimeout((int) TimeUnit.MILLISECONDS.convert(10, TimeUnit.MINUTES));
    String fullURL = "http://localhost:" + result.serverPort() + url;
    return wc.getPage(fullURL).getWebResponse();
  }

  @Override
  public WebResponse ping(String url) throws IOException {
    return ping(url, new WebClient());
  }

  @Override
  public Server restart() throws Exception {
    stop();
    start();
    return this;
  }

  @Override
  public String toString() {
    return "Generic Server" + (result != null ? "; port:" + result.serverPort() : "");
  }

  @Override
  public File getWorkingDirectory() {
    return workingDir;
  }

  public AppServer getAppServer() {
    if (server == null) {
      server = factory.createAppServer(installation);
    }
    return server;
  }

  @Override
  public File getTcConfigFile() {
    return tcConfigFile;
  }

}
