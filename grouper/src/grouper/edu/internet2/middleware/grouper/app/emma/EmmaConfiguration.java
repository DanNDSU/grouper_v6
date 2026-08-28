package edu.internet2.middleware.grouper.app.emma;

import edu.internet2.middleware.grouper.app.provisioning.GrouperProvisioningConfiguration;

public class EmmaConfiguration extends GrouperProvisioningConfiguration {

  /**
   * config id of the WsBearerToken (basic auth) external system that holds
   * the Emma endpoint and the public/private API key credentials.
   */
  private String emmaExternalSystemConfigId;

  @Override
  public void configureSpecificSettings() {
    this.emmaExternalSystemConfigId = this.retrieveConfigString("emmaExternalSystemConfigId", true);
  }

  /**
   * Get the Emma external system config ID
   * @return the Emma external system config ID
   */
  public String getEmmaExternalSystemConfigId() {
    return emmaExternalSystemConfigId;
  }

  /**
   * Set the Emma external system config ID
   * @param emmaExternalSystemConfigId the external system config ID to set
   */
  public void setEmmaExternalSystemConfigId(String emmaExternalSystemConfigId) {
    this.emmaExternalSystemConfigId = emmaExternalSystemConfigId;
  }

}
