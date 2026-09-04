package edu.internet2.middleware.grouper.app.emma;

import java.util.HashMap;
import java.util.Map;

/**
 * Fluent input bean describing how an Emma provisioner should be configured
 * for one test.
 *
 * Modeled on DuoProvisionerTestConfigInput / TeamsChannelProvisionerTestConfigInput.
 */
public class EmmaProvisionerTestConfigInput {

  /** default to myEmmaProvisioner */
  private String configId = "myEmmaProvisioner";

  /** the WsBearerToken external system config id (grouper.wsBearerToken.&lt;id&gt;.*) */
  private String emmaExternalSystemConfigId = "emmaDev";

  /** select all groups/entities up front rather than looking them up one at a time */
  private boolean selectAll = true;

  /** extra config by suffix and value */
  private Map<String, String> extraConfig = new HashMap<String, String>();

  public String getConfigId() {
    return configId;
  }

  public EmmaProvisionerTestConfigInput assignConfigId(String configId) {
    this.configId = configId;
    return this;
  }

  public String getEmmaExternalSystemConfigId() {
    return emmaExternalSystemConfigId;
  }

  public EmmaProvisionerTestConfigInput assignEmmaExternalSystemConfigId(String emmaExternalSystemConfigId) {
    this.emmaExternalSystemConfigId = emmaExternalSystemConfigId;
    return this;
  }

  public boolean isSelectAll() {
    return selectAll;
  }

  public EmmaProvisionerTestConfigInput assignSelectAll(boolean selectAll) {
    this.selectAll = selectAll;
    return this;
  }

  /**
   * extra config by suffix and value
   * @param suffix
   * @param value
   * @return this for chaining
   */
  public EmmaProvisionerTestConfigInput addExtraConfig(String suffix, String value) {
    this.extraConfig.put(suffix, value);
    return this;
  }

  public Map<String, String> getExtraConfig() {
    return this.extraConfig;
  }

}
