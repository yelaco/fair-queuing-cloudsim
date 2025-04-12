package org.advancedcloud.utils;

import org.cloudsimplus.cloudlets.CloudletSimple;
import org.cloudsimplus.utilizationmodels.UtilizationModel;

public class CloudletDeadline extends CloudletSimple {
  private long deadline;

  public CloudletDeadline(long length, int pesNumber, UtilizationModel utilizationModel, long deadline) {
    super(length, pesNumber, utilizationModel);
    this.deadline = deadline;
  }

  public long getDeadline() {
    return deadline;
  }

  public void setDeadline(long deadline) {
    this.deadline = deadline;
  }
}
