/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.test.server.appserver;

import com.tc.exception.ImplementMe;
import com.tc.test.AppServerInfo;
import com.tc.test.TestConfigObject;
import com.tc.test.server.appserver.glassfishv1.GlassfishV1AppServerFactory;
import com.tc.test.server.appserver.glassfishv2.GlassfishV2AppServerFactory;
import com.tc.test.server.appserver.glassfishv3.GlassfishV3AppServerFactory;
import com.tc.test.server.appserver.jboss3x.JBoss3xAppServerFactory;
import com.tc.test.server.appserver.jboss42x.JBoss42xAppServerFactory;
import com.tc.test.server.appserver.jboss4x.JBoss4xAppServerFactory;
import com.tc.test.server.appserver.jboss51x.JBoss51xAppServerFactory;
import com.tc.test.server.appserver.jboss6x.JBoss6xAppServerFactory;
import com.tc.test.server.appserver.jboss72x.JBoss72xAppServerFactory;
import com.tc.test.server.appserver.jboss7x.JBoss7xAppServerFactory;
import com.tc.test.server.appserver.jetty6x.Jetty6xAppServerFactory;
import com.tc.test.server.appserver.jetty7x.Jetty7xAppServerFactory;
import com.tc.test.server.appserver.jetty8x.Jetty8xAppServerFactory;
import com.tc.test.server.appserver.jetty9x.Jetty9xAppServerFactory;
import com.tc.test.server.appserver.resin31x.Resin31xAppServerFactory;
import com.tc.test.server.appserver.resin40x.Resin40xAppServerFactory;
import com.tc.test.server.appserver.tomcat5x.Tomcat5xAppServerFactory;
import com.tc.test.server.appserver.tomcat6x.Tomcat6xAppServerFactory;
import com.tc.test.server.appserver.tomcat7x.Tomcat7xAppServerFactory;
import com.tc.test.server.appserver.was7x.Was7xAppServerFactory;
import com.tc.test.server.appserver.was8x.Was8xAppServerFactory;
import com.tc.test.server.appserver.weblogic10x.Weblogic10xAppServerFactory;
import com.tc.test.server.appserver.weblogic12x.Weblogic12xAppServerFactory;

import java.io.File;
import java.util.Properties;

/**
 * This factory is meant to be used by the general public. The properties file supplied in obtaining an instance may be
 * blank, which will fall on the default appserver implementations. This class should be the only point for reference in
 * creating a working appserver. Never instantiate specific appserver classes explicitly.
 */
public abstract class AppServerFactory {

  public abstract AppServerParameters createParameters(String instanceName, Properties props);

  public AppServerParameters createParameters(final String instanceName) {
    return createParameters(instanceName, new Properties());
  }

  public abstract AppServer createAppServer(AppServerInstallation installation);

  public abstract AppServerInstallation createInstallation(File home, File workingDir, AppServerInfo appServerInfo)
      throws Exception;

  public static final AppServerFactory createFactoryFromProperties() {
    AppServerInfo appServerInfo = TestConfigObject.getInstance().appServerInfo();
    String factoryName = appServerInfo.getName();
    String majorVersion = appServerInfo.getMajor();
    String minorVersion = appServerInfo.getMinor();
    System.out.println("APPSERVERINFO: " + appServerInfo);

    switch (appServerInfo.getId()) {
      case AppServerInfo.TOMCAT:
        if ("5".equals(majorVersion)) return new Tomcat5xAppServerFactory();
        if ("6".equals(majorVersion)) return new Tomcat6xAppServerFactory();
        if ("7".equals(majorVersion)) return new Tomcat7xAppServerFactory();
        break;
      case AppServerInfo.WEBLOGIC:
        if ("10".equals(majorVersion)) return new Weblogic10xAppServerFactory();
        if ("12".equals(majorVersion)) return new Weblogic12xAppServerFactory();
        break;
      case AppServerInfo.JBOSS:
        if ("3".equals(majorVersion)) return new JBoss3xAppServerFactory();
        if ("4".equals(majorVersion)) {
          if (minorVersion.startsWith("0")) {
            return new JBoss4xAppServerFactory();
          } else if (minorVersion.startsWith("2")) { return new JBoss42xAppServerFactory(); }
        }
        if (factoryName.contains("eap")) {
          if ("6".equals(majorVersion)) { return new JBoss72xAppServerFactory(); }
        } else {
          if ("5".equals(majorVersion) && minorVersion.startsWith("1")) { return new JBoss51xAppServerFactory(); }
          if ("6".equals(majorVersion)) { return new JBoss6xAppServerFactory(); }
          if ("7".equals(majorVersion) && minorVersion.startsWith("1")) { return new JBoss7xAppServerFactory(); }
          if ("7".equals(majorVersion) && minorVersion.startsWith("2")) { return new JBoss72xAppServerFactory(); }
        }
        break;
      case AppServerInfo.GLASSFISH:
        if ("v1".equals(majorVersion)) return new GlassfishV1AppServerFactory();
        if ("v2".equals(majorVersion)) return new GlassfishV2AppServerFactory();
        if ("3".equals(majorVersion)) return new GlassfishV3AppServerFactory();
        break;
      case AppServerInfo.JETTY:
        if ("6".equals(majorVersion)) return new Jetty6xAppServerFactory();
        if ("7".equals(majorVersion)) return new Jetty7xAppServerFactory();
        if ("8".equals(majorVersion)) return new Jetty8xAppServerFactory();
        if ("9".equals(majorVersion)) return new Jetty9xAppServerFactory();
        break;
      case AppServerInfo.RESIN:
        if ("3".equals(majorVersion)) return new Resin31xAppServerFactory();
        if ("4".equals(majorVersion)) return new Resin40xAppServerFactory();
        break;
      case AppServerInfo.WEBSPHERE:
        if ("7".equals(majorVersion)) return new Was7xAppServerFactory();
        if ("8".equals(majorVersion)) return new Was8xAppServerFactory();
        break;
    }

    throw new ImplementMe("App server named '" + factoryName + "' with major version " + majorVersion
                          + " is not yet supported.");
  }
}
