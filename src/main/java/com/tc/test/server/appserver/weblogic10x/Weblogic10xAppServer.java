/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.test.server.appserver.weblogic10x;

import org.codehaus.cargo.container.InstalledLocalContainer;
import org.codehaus.cargo.container.configuration.LocalConfiguration;
import org.codehaus.cargo.container.property.GeneralPropertySet;
import org.codehaus.cargo.container.weblogic.WebLogic10xInstalledLocalContainer;

import com.tc.test.server.appserver.AppServerParameters;
import com.tc.test.server.appserver.weblogic.WeblogicAppServerBase;
import com.tc.util.ReplaceLine;

import java.io.File;
import java.io.IOException;

/**
 * Weblogic10x AppServer implementation
 */
public final class Weblogic10xAppServer extends WeblogicAppServerBase {

  public Weblogic10xAppServer(Weblogic10xAppServerInstallation installation) {
    super(installation);
  }

  @Override
  public File serverInstallDirectory() {
    return new File(super.serverInstallDirectory(), "wlserver");
  }

  @Override
  protected String cargoServerKey() {
    return "weblogic103x";
  }

  @Override
  protected InstalledLocalContainer container(LocalConfiguration config, AppServerParameters params) {
    return new TCWebLogic10xInstalledLocalContainer(config, params);
  }

  @Override
  protected void setConfigProperties(LocalConfiguration config) throws Exception {
    config.setProperty(GeneralPropertySet.HOSTNAME, "0.0.0.0");
  }

  private static class TCWebLogic10xInstalledLocalContainer extends WebLogic10xInstalledLocalContainer {

    public TCWebLogic10xInstalledLocalContainer(LocalConfiguration configuration, AppServerParameters params) {
      super(configuration);
    }

    @Override
    protected void startInternal() throws Exception {
      adjustConfig();
      super.startInternal();
    }

    private void adjustConfig() {
      String insert = "";
      insert += "    <native-io-enabled>false</native-io-enabled>\n";
      insert += "    <socket-reader-timeout-min-millis>1000</socket-reader-timeout-min-millis>\n";
      insert += "    <socket-reader-timeout-max-millis>1000</socket-reader-timeout-max-millis>\n";

      ReplaceLine.Token[] tokens = new ReplaceLine.Token[1];
      tokens[0] = new ReplaceLine.Token(28, "    <listen-port>", insert + "    <listen-port>");

      try {
        ReplaceLine.parseFile(tokens, new File(getConfiguration().getHome(), "/config/config.xml"));
      } catch (IOException ioe) {
        throw new RuntimeException(ioe);
      }
    }

    @Override
    public void addExtraClasspath(String location) {
      super.addExtraClasspath(location);
    }
  }
}
