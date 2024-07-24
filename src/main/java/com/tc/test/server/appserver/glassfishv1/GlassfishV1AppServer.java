/*
 * Copyright 2003-2008 Terracotta, Inc.
 * Copyright Super iPaaS Integration LLC, an IBM Company 2024
 */
package com.tc.test.server.appserver.glassfishv1;

import com.tc.test.server.appserver.AppServerParameters;
import com.tc.test.server.appserver.glassfish.AbstractGlassfishAppServer;
import com.tc.test.server.appserver.glassfish.GlassfishAppServerInstallation;

import java.io.File;

/**
 * Glassfish AppServer implementation
 */
public final class GlassfishV1AppServer extends AbstractGlassfishAppServer {

  public GlassfishV1AppServer(final GlassfishAppServerInstallation installation) {
    super(installation);
  }

  @Override
  protected String[] getDisplayCommand(final String script, final AppServerParameters params) {
    return new String[] { script, "display" };
  }

  @Override
  protected File getStartScript(final AppServerParameters params) {
    return getInstanceFile("bin/" + getPlatformScript("startserv"));
  }

  @Override
  protected File getStopScript(final AppServerParameters params) {
    return getInstanceFile("bin/" + getPlatformScript("stopserv"));
  }
}