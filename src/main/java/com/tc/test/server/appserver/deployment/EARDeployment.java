/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.test.server.appserver.deployment;

import java.util.Properties;

public class EARDeployment implements Deployment {

  private final FileSystemPath earFile;
  private final boolean        clustered;
  private final Properties     properties;

  public EARDeployment(FileSystemPath earFile) {
    this.earFile = earFile;
    this.clustered = false;
    this.properties = new Properties();
  }

  public FileSystemPath getFileSystemPath() {
    return earFile;
  }

  public boolean isClustered() {
    return clustered;
  }

  public Properties properties() {
    return properties;
  }

}
