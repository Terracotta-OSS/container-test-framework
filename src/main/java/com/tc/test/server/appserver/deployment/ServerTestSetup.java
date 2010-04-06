/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.test.server.appserver.deployment;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import com.tc.test.AppServerInfo;
import com.tc.test.TestConfigObject;
import com.tc.test.server.appserver.StandardAppServerParameters;
import com.tc.test.server.appserver.ValveDefinition;
import com.tc.test.server.appserver.load.LowMemWorkaround;
import com.tc.test.server.util.Util;
import com.tc.text.Banner;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import junit.extensions.TestSetup;
import junit.framework.TestSuite;

public class ServerTestSetup extends TestSetup {

  private static final String EXPRESS_MODE_LOAD_CLASS = "org.terracotta.session.TerracottaWeblogic10xSessionFilter";
  private final Class      testClass;
  private final Collection extraJvmArgs;
  private final boolean    persistentMode;
  private ServerManager    sm;

  public ServerTestSetup(Class testClass) {
    this(testClass, false, Collections.EMPTY_LIST);
  }

  public ServerTestSetup(Class testClass, boolean persistentMode, Collection extraJvmArgs) {
    super(new TestSuite(testClass));
    this.testClass = testClass;
    this.persistentMode = persistentMode;
    this.extraJvmArgs = extraJvmArgs;
  }

  @Override
  protected void setUp() throws Exception {
    if (shouldDisable()) return;
    super.setUp();
    if (TestConfigObject.getInstance().isExpressMode()) {
      Banner.infoBanner("Running appserver in express mode. DSO is disabled");
      GenericServer.setDsoEnabled(false);
    }
    getServerManager();
  }

  @Override
  protected void tearDown() throws Exception {
    if (sm != null) {
      ServerManagerUtil.stopAndRelease(sm);
    }
  }

  protected ServerManager getServerManager() {
    if (sm == null) {
      try {
        sm = ServerManagerUtil.startAndBind(testClass, isWithPersistentStore(), extraJvmArgs);
      } catch (Exception e) {
        throw new RuntimeException("Unable to create server manager", e);
      }
    }
    return sm;
  }

  public AppServerInfo appServerInfo() {
    return TestConfigObject.getInstance().appServerInfo();
  }

  public DeploymentBuilder makeDeploymentBuilder() throws IOException {
    return getServerManager().makeDeploymentBuilder();
  }

  public DeploymentBuilder makeDeploymentBuilder(String warFileName) {
    return getServerManager().makeDeploymentBuilder(warFileName);
  }

  public boolean isWithPersistentStore() {
    return persistentMode;
  }

  public boolean shouldDisable() {
    if (LowMemWorkaround.lessThan2Gb()) {
      Banner.warnBanner("NOT RUNNNING TEST SINCE THIS MACHINE DOES NOT HAVE AT LEAST 2GB MEMORY");
      return true;
    }

    for (Enumeration e = ((TestSuite) fTest).tests(); e.hasMoreElements();) {
      Object o = e.nextElement();
      if (o instanceof AbstractDeploymentTestCase && ((AbstractDeploymentTestCase) o).shouldDisable()) { return true; }
    }
    return false;
  }

  public boolean isSessionLockingTrue() {
    return true;
  }

  private String getTcConfigUrl() {
    return "localhost:" + getServerManager().getServerTcConfig().getDsoPort();
  }

  private boolean useFilter() {
    return appServerInfo().getId() == AppServerInfo.WEBLOGIC || appServerInfo().getId() == AppServerInfo.JETTY
           || appServerInfo().getId() == AppServerInfo.WEBSPHERE;
  }

  private ValveDefinition makeValveDef() {
    ValveDefinition valve = new ValveDefinition(Mappings.getClassForAppServer(appServerInfo()));
    for (Entry<String, String> attr : getConfigAttributes().entrySet()) {
      valve.setAttribute(attr.getKey(), attr.getValue());
    }
    return valve;
  }

  private Map<String, String> getConfigAttributes() {
    Map<String, String> attrs = new HashMap();
    attrs.put("tcConfigUrl", getTcConfigUrl());

    if (getSessionLocking() != null) {
      attrs.put("sessionLocking", getSessionLocking().toString());
    }

    if (getSynchronousWrite() != null) {
      attrs.put("synchronousWrite", getSynchronousWrite().toString());
    }
    return attrs;
  }

  // override if desired
  protected Boolean getSessionLocking() {
    return null;
  }

  // override if desired
  protected Boolean getSynchronousWrite() {
    return null;
  }

  protected void configureExpressModeIfNeeded(DeploymentBuilder builder) {
    // skip this step if not running express mode
    if (!TestConfigObject.getInstance().isExpressMode()) {
      return;
    }
    
    if (useFilter()) {
      try {
        builder.addDirectoryOrJARContainingClass(Class
            .forName(EXPRESS_MODE_LOAD_CLASS));
      } catch (ClassNotFoundException e1) {
        throw new RuntimeException(e1);
      }

      Map<String, String> filterConfig = getConfigAttributes();

      Class filter;
      try {
        filter = Class.forName(Mappings.getClassForAppServer(appServerInfo()));
      } catch (ClassNotFoundException e) {
        throw new RuntimeException(e);
      }
      builder.addFilter("terracotta-filter", "/*", filter, filterConfig, EnumSet.allOf(WARBuilder.Dispatcher.class));
    }

    builder.addFileAsResource(makeJbossContextXml(appServerInfo()), "WEB-INF");
  }

  private File makeJbossContextXml(AppServerInfo appServerInfo) {
    File tmp = new File(getServerManager().getSandbox(), "context.xml");
    String xml = "";
    xml += "<Context>\n";
    xml += "  " + makeValveDef().toString() + "\n";
    xml += "</Context>\n";

    FileOutputStream out = null;
    try {
      out = new FileOutputStream(tmp);
      out.write(xml.getBytes());
    } catch (IOException ioe) {
      throw new RuntimeException(ioe);
    } finally {
      IOUtils.closeQuietly(out);
    }

    return tmp;
  }
  
  protected void addExpressModeParamsIfNeeded(StandardAppServerParameters params) {
    // skip this step if not running express mode
    if (!TestConfigObject.getInstance().isExpressMode()) {
      return;
    }
    
    File expressJar;
    try {
      expressJar = new File(Util.jarFor(Class
                                             .forName(EXPRESS_MODE_LOAD_CLASS)));
    } catch (ClassNotFoundException e1) {
      throw new RuntimeException(e1);
    }
    File sandBoxArtifact = new File(getServerManager().getTempDir(), expressJar.getName());

    if (!sandBoxArtifact.exists()) {
      // copy the express jar into the test temp dir since that likely won't have any spaces in it
      try {
        FileUtils.copyFile(expressJar, sandBoxArtifact);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    params.addTomcatServerJar(sandBoxArtifact.getAbsolutePath());

    if (appServerInfo().getId() == AppServerInfo.TOMCAT) {
      params.addValve(makeValveDef());
    }
  }

  private static class Mappings {
    private static final Map<String, String> mappings = new HashMap<String, String>();

    static {
      mappings.put("jboss-4.0.", "TerracottaJboss40xSessionValve");
      mappings.put("jboss-4.2.", "TerracottaJboss42xSessionValve");
      mappings.put("jboss-5.1.", "TerracottaJboss51xSessionValve");
      mappings.put("weblogic-9.", "TerracottaWeblogic9xSessionFilter");
      mappings.put("weblogic-10.", "TerracottaWeblogic10xSessionFilter");
      mappings.put("jetty-6.1.", "TerracottaJetty61xSessionFilter");
      mappings.put("tomcat-5.0.", "TerracottaTomcat50xSessionValve");
      mappings.put("tomcat-5.5.", "TerracottaTomcat55xSessionValve");
      mappings.put("tomcat-6.0.", "TerracottaTomcat60xSessionValve");
      mappings.put("websphere-6.1.", "TerracottaWebsphere61xSessionFilter");
    }

    static String getClassForAppServer(AppServerInfo info) {
      for (String key : mappings.keySet()) {
        if (info.toString().startsWith(key)) { return "org.terracotta.session." + mappings.get(key); }

      }

      throw new AssertionError("no mapping for " + info);
    }
  }
}
