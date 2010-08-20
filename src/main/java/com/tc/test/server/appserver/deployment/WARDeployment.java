/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.test.server.appserver.deployment;


public class WARDeployment implements Deployment {

  private final FileSystemPath warFile;
  private boolean clustered;

  public WARDeployment(FileSystemPath warFile, boolean clustered) {
    this.warFile = warFile;
    this.clustered = clustered;
  }
  
  public FileSystemPath getFileSystemPath() {
    return warFile;
  }
  
  public boolean isClustered() {
    return clustered;
  }

}
