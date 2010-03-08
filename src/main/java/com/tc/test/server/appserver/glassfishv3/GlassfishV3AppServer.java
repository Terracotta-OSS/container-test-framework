/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.test.server.appserver.glassfishv3;

import com.tc.process.Exec;
import com.tc.process.Exec.Result;
import com.tc.test.server.appserver.AppServerParameters;
import com.tc.test.server.appserver.glassfish.AbstractGlassfishAppServer;
import com.tc.test.server.appserver.glassfish.GlassfishAppServerInstallation;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class GlassfishV3AppServer extends AbstractGlassfishAppServer {

  public GlassfishV3AppServer(final GlassfishAppServerInstallation installation) {
    super(installation);
  }

  @Override
  protected String[] getDisplayCommand(final String script, final AppServerParameters params) {
    return new String[] { script, "--domaindir=" + sandboxDirectory(), params.instanceName() };
  }

  @Override
  protected void modifyStartupCommand(final List cmd) {
    //cmd.add(0, "-Dcom.sun.aas.promptForIdentity=true");
  }

  @Override
  protected File getStartScript(final AppServerParameters params) {
    return new File(new File(new File(serverInstallDirectory(), "glassfish"), "bin"), getPlatformScript("startserv"));
  }

  @Override
  protected File getStopScript(final AppServerParameters params) {
    return new File(new File(new File(serverInstallDirectory(), "glassfish"), "bin"), getPlatformScript("stopserv"));
  }

  @Override
  protected void createDomain(final AppServerParameters params) throws Exception {
    File asAdminScript = getAsadminScript();

    List cmd = new ArrayList();
    cmd.add(asAdminScript.getAbsolutePath());
    cmd.add("--interactive=false");
    cmd.add("--user");
    cmd.add(ADMIN_USER);
//    cmd.add("--passwordfile");
//    cmd.add(getPasswdFile().getAbsolutePath());
    cmd.add("create-domain");
    cmd.add("--adminport");
    cmd.add(String.valueOf(adminPort));
    cmd.add("--instanceport");
    cmd.add(String.valueOf(httpPort));
//    cmd.add("--savemasterpassword=true");
    cmd.add("--domaindir=" + sandboxDirectory());
    cmd.add("--domainproperties");
    cmd.add("jms.port=" + pc.chooseRandomPort() + ":" + "orb.listener.port=" + pc.chooseRandomPort() + ":"
            + "http.ssl.port=" + pc.chooseRandomPort() + ":" + "orb.ssl.port=" + pc.chooseRandomPort() + ":"
            + "orb.mutualauth.port=" + pc.chooseRandomPort() + ":" + "domain.jmxPort=" + pc.chooseRandomPort());
    cmd.add("--savelogin=true");
    cmd.add("--nopassword=true");
    cmd.add(params.instanceName());

    Result result = Exec.execute((String[]) cmd.toArray(new String[] {}), null, null, asAdminScript.getParentFile());

    if (result.getExitCode() != 0) { throw new RuntimeException(result.toString()); }
  }

}
