/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.test.server.appserver.jboss72x;

import org.apache.commons.io.FileUtils;
import org.codehaus.cargo.container.InstalledLocalContainer;
import org.codehaus.cargo.container.configuration.LocalConfiguration;
import org.codehaus.cargo.container.jboss.JBoss72xInstalledLocalContainer;
import org.codehaus.cargo.container.jboss.JBossPropertySet;
import org.codehaus.cargo.container.property.GeneralPropertySet;
import org.codehaus.cargo.container.spi.jvm.JvmLauncher;

import com.tc.test.AppServerInfo;
import com.tc.test.server.ServerParameters;
import com.tc.test.server.ServerResult;
import com.tc.test.server.appserver.AppServerParameters;
import com.tc.test.server.appserver.cargo.CargoAppServer;
import com.tc.test.server.appserver.jboss_common.JBossHelper;
import com.tc.test.server.util.AppServerUtil;

import java.io.File;
import java.util.Collection;
import java.util.concurrent.TimeUnit;

/**
 * JBoss7x AppServer implementation
 */
public final class JBoss72xAppServer extends CargoAppServer {

  public JBoss72xAppServer(JBoss72xAppServerInstallation installation) {
    super(installation);
  }

  // NOTE: JBoss EAP 6.1.0 packages JBoss AS 7.2.0
  @Override
  protected String cargoServerKey() {
    return "jboss72x";
  }

  @Override
  protected InstalledLocalContainer container(LocalConfiguration config, AppServerParameters params) {
    return new TCJBoss72xInstalledLocalContainer(config, params.sars(), appServerInfo(), params);
  }

  @Override
  protected void setConfigProperties(LocalConfiguration config) throws Exception {
    config.setProperty(GeneralPropertySet.RMI_PORT, Integer.toString(AppServerUtil.getPort()));
    config.setProperty(JBossPropertySet.JBOSS_AJP_PORT, Integer.toString(AppServerUtil.getPort()));
    config.setProperty(JBossPropertySet.JBOSS_JMX_PORT, Integer.toString(AppServerUtil.getPort()));
    config.setProperty(JBossPropertySet.JBOSS_JRMP_PORT, Integer.toString(AppServerUtil.getPort()));
    config.setProperty(JBossPropertySet.JBOSS_NAMING_PORT, Integer.toString(AppServerUtil.getPort()));
    config.setProperty(JBossPropertySet.JBOSS_CLASSLOADING_WEBSERVICE_PORT, Integer.toString(AppServerUtil.getPort()));
    config.setProperty(JBossPropertySet.JBOSS_JRMP_INVOKER_PORT, Integer.toString(AppServerUtil.getPort()));
    config.setProperty(JBossPropertySet.JBOSS_INVOKER_POOL_PORT, Integer.toString(AppServerUtil.getPort()));
    config.setProperty(JBossPropertySet.JBOSS_REMOTING_TRANSPORT_PORT, Integer.toString(AppServerUtil.getPort()));
    config.setProperty(JBossPropertySet.JBOSS_EJB3_REMOTING_PORT, Integer.toString(AppServerUtil.getPort()));
    config.setProperty(JBossPropertySet.JBOSS_TRANSACTION_RECOVERY_MANAGER_PORT,
                       Integer.toString(AppServerUtil.getPort()));
    config.setProperty(JBossPropertySet.JBOSS_TRANSACTION_STATUS_MANAGER_PORT,
                       Integer.toString(AppServerUtil.getPort()));
    config.setProperty(JBossPropertySet.REMOTEDEPLOY_PORT, Integer.toString(AppServerUtil.getPort()));
    config.setProperty(JBossPropertySet.JBOSS_MANAGEMENT_HTTP_PORT, Integer.toString(AppServerUtil.getPort()));
    config.setProperty(JBossPropertySet.JBOSS_MANAGEMENT_NATIVE_PORT, Integer.toString(AppServerUtil.getPort()));
  }

  @Override
  public ServerResult start(ServerParameters rawParams) throws Exception {
    ServerResult result = super.start(rawParams);
    JBossHelper.waitUntilWarsDeployed(this.instanceDir(), TimeUnit.MINUTES.toMillis(5));
    return result;
  }

  @Override
  public void stop(ServerParameters rawParams) {
    try {
      super.stop(rawParams);
    } finally {
      FileUtils.deleteQuietly(new File(this.instanceDir(), "data"));
      FileUtils.deleteQuietly(new File(this.instanceDir(), "deployments"));
    }
  }

  private static class TCJBoss72xInstalledLocalContainer extends JBoss72xInstalledLocalContainer {
    private final Collection          sars;
    private final AppServerInfo       appServerInfo;
    private final AppServerParameters params;

    public TCJBoss72xInstalledLocalContainer(LocalConfiguration configuration, Collection sars,
                                             AppServerInfo appServerInfo, AppServerParameters params) {
      super(configuration);
      this.sars = sars;
      this.appServerInfo = appServerInfo;
      this.params = params;
    }

    @Override
    protected void doStart(JvmLauncher java) throws Exception {
      // JBossHelper.startupActions(new File(getConfiguration().getHome()), sars, appServerInfo, params);
      super.doStart(java);
    }
  }

}
