/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.tc.test.server.util;

import com.tc.test.server.appserver.AppServerParameters;
import com.tc.test.server.appserver.ValveDefinition;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class ParamsWithRetry implements AppServerParameters {

  private final AppServerParameters delegate;
  private final int                 retryNum;

  public ParamsWithRetry(final AppServerParameters delegate, final int retryNum) {
    this.delegate = delegate;
    this.retryNum = retryNum;
  }

  public List<String> extraClasspath() {
    return delegate.extraClasspath();
  }

  public String instanceName() {
    return delegate.instanceName() + (retryNum == 0 ? "" : "-retry" + retryNum);
  }

  public String jvmArgs() {
    return delegate.jvmArgs();
  }

  public Properties properties() {
    return delegate.properties();
  }

  public Collection sars() {
    return delegate.sars();
  }

  public Map wars() {
    return delegate.wars();
  }

  public Collection<ValveDefinition> valves() {
    return delegate.valves();
  }

  public Collection<String> tomcatServerJars() {
    return delegate.tomcatServerJars();
  }

  public Map deployments() {
    return delegate.deployments();
  }
}