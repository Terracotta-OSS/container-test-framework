/*
 * Copyright 2003-2008 Terracotta, Inc.
 * Copyright Super iPaaS Integration LLC, an IBM Company 2024
 */
package com.tc.test.server.appserver.deployment;

import java.util.Properties;

public class WARDeployment implements Deployment {

  private final FileSystemPath warFile;
  private final boolean        clustered;
  private final Properties     properties;

  public WARDeployment(FileSystemPath warFile, boolean clustered) {
    this.warFile = warFile;
    this.clustered = clustered;
    this.properties = new Properties();
  }

  public FileSystemPath getFileSystemPath() {
    return warFile;
  }

  public boolean isClustered() {
    return clustered;
  }

  public Properties properties() {
    return properties;
  }

}
