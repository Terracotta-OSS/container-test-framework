/*
 * Copyright 2003-2008 Terracotta, Inc.
 * Copyright Super iPaaS Integration LLC, an IBM Company 2024
 */
package com.tc.test.server.appserver.tomcat5x;

import org.codehaus.cargo.container.InstalledLocalContainer;
import org.codehaus.cargo.container.configuration.LocalConfiguration;
import org.codehaus.cargo.container.property.GeneralPropertySet;
import org.codehaus.cargo.container.tomcat.Tomcat5xInstalledLocalContainer;
import org.codehaus.cargo.container.tomcat.TomcatPropertySet;

import com.tc.test.AppServerInfo;
import com.tc.test.server.appserver.AppServerParameters;
import com.tc.test.server.appserver.cargo.CargoAppServer;
import com.tc.test.server.appserver.tomcat.TomcatStartupActions;
import com.tc.test.server.util.AppServerUtil;

/**
 * Tomcat5x AppServer implementation
 */
public final class Tomcat5xAppServer extends CargoAppServer {

  private final AppServerInfo appServerInfo;

  public Tomcat5xAppServer(Tomcat5xAppServerInstallation installation) {
    super(installation);
    appServerInfo = installation.appServerInfo();
  }

  @Override
  protected String cargoServerKey() {
    return "tomcat5x";
  }

  @Override
  protected InstalledLocalContainer container(LocalConfiguration config, AppServerParameters params) {
    return new TCTomcat5xInstalledLocalContainer(config, params, appServerInfo);
  }

  private static class TCTomcat5xInstalledLocalContainer extends Tomcat5xInstalledLocalContainer {

    private final AppServerParameters params;
    private final AppServerInfo       appServerInfo;

    public TCTomcat5xInstalledLocalContainer(LocalConfiguration config, AppServerParameters params,
                                             AppServerInfo appServerInfo) {
      super(config);
      this.params = params;
      this.appServerInfo = appServerInfo;
      config.setProperty(GeneralPropertySet.RMI_PORT, Integer.toString(AppServerUtil.getPort()));
      config.setProperty(TomcatPropertySet.AJP_PORT, Integer.toString(AppServerUtil.getPort()));
      config.setProperty(TomcatPropertySet.CONNECTOR_EMPTY_SESSION_PATH, "false");
    }

    @Override
    protected void startInternal() throws Exception {
      int line = appServerInfo.getMinor().startsWith("0.") ? 45 : 60;
      TomcatStartupActions.modifyConfig(params, this, line);
      TomcatStartupActions.configureManagerApp(params, this);
      super.startInternal();
    }
  }

}
