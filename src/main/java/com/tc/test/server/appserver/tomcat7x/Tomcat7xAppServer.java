/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.test.server.appserver.tomcat7x;

import org.codehaus.cargo.container.InstalledLocalContainer;
import org.codehaus.cargo.container.State;
import org.codehaus.cargo.container.configuration.LocalConfiguration;
import org.codehaus.cargo.container.property.GeneralPropertySet;
import org.codehaus.cargo.container.tomcat.Tomcat7xInstalledLocalContainer;
import org.codehaus.cargo.container.tomcat.TomcatPropertySet;

import com.tc.test.server.appserver.AppServerParameters;
import com.tc.test.server.appserver.cargo.CargoAppServer;
import com.tc.test.server.appserver.tomcat.TomcatStartupActions;
import com.tc.test.server.util.AppServerUtil;

/**
 * Tomcat6x AppServer implementation
 */
public final class Tomcat7xAppServer extends CargoAppServer {

  public Tomcat7xAppServer(Tomcat7xAppServerInstallation installation) {
    super(installation);
  }

  @Override
  protected String cargoServerKey() {
    return "tomcat7x";
  }

  @Override
  protected InstalledLocalContainer container(LocalConfiguration config, AppServerParameters params) {
    return new TCTomcat7xInstalledLocalContainer(config, params);
  }

  private static class TCTomcat7xInstalledLocalContainer extends Tomcat7xInstalledLocalContainer {

    private final AppServerParameters params;

    public TCTomcat7xInstalledLocalContainer(LocalConfiguration config, AppServerParameters params) {
      super(config);
      this.params = params;
      config.setProperty(GeneralPropertySet.RMI_PORT, Integer.toString(AppServerUtil.getPort()));
      config.setProperty(TomcatPropertySet.AJP_PORT, Integer.toString(AppServerUtil.getPort()));
    }

    @Override
    protected void setState(State state) {
      if (state.isStarting()) {
        TomcatStartupActions.modifyConfig(params, this, 47);
        TomcatStartupActions.configureManagerApp(params, this);
      }

      super.setState(state);
    }
  }

}
