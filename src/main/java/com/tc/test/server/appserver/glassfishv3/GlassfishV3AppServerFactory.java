/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.test.server.appserver.glassfishv3;

import com.tc.test.AppServerInfo;
import com.tc.test.server.appserver.AppServer;
import com.tc.test.server.appserver.AppServerFactory;
import com.tc.test.server.appserver.AppServerInstallation;
import com.tc.test.server.appserver.AppServerParameters;
import com.tc.test.server.appserver.StandardAppServerParameters;
import com.tc.test.server.appserver.glassfish.GlassfishAppServerInstallation;

import java.io.File;
import java.util.Properties;

public class GlassfishV3AppServerFactory extends AppServerFactory {

  public GlassfishV3AppServerFactory(final ProtectedKey protectedKey) {
    super(protectedKey);
  }

  @Override
  public AppServerParameters createParameters(final String instanceName, final Properties props) {
    return new StandardAppServerParameters(instanceName, props);
  }

  @Override
  public AppServerInstallation createInstallation(final File home, final File workingDir, final AppServerInfo appServerInfo) throws Exception {
    GlassfishAppServerInstallation install = new GlassfishAppServerInstallation(home, workingDir, appServerInfo);
    return install;
  }

  @Override
  public AppServer createAppServer(final AppServerInstallation installation) {
    return new GlassfishV3AppServer((GlassfishAppServerInstallation) installation);
  }
}