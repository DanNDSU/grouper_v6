package edu.internet2.middleware.grouper.app.emma;

import java.util.HashMap;
import java.util.Map;

/**
 * Fluent input bean describing how an Emma provisioner should be configured for
 * one test.
 *
 * Modeled on TeamsChannelProvisionerTestConfigInput / AzureProvisionerTestConfigInput.
 */
public class EmmaProvisionerTestConfigInput {

  /** default to myEmmaProvisioner */
  private String configId = "myEmmaProvisioner";

  /**
   * the WsBearerToken external system config id that holds the Emma endpoint and
   * the public/private API key basic-auth credentials.  The mock service reads
   * grouper.wsBearerToken.&lt;id&gt;.* the same way the real Emma API commands do.
   */
  private String emmaExternalSystemConfigId = "myEmma";

  /** run against real Emma (https://api.e2ma.net/) instead of the mock service */
  private boolean realEmma = false;

  /**
   * which Grouper group field the Emma group_name comes from.  Emma group names
   * are not constrained the way a Teams channel name is, so this defaults to the
   * full group name.
   */
  private String groupNameMapping = "name";

  /**
   * 2 (id, name).  Emma groups have no description, so unlike most targets there
   * is nothing else to map.
   */
  private int groupAttributeCount = 2;

  /**
   * number of entity (member) attributes.  4 = id, email, firstName, lastName.
   * Bump this and add a targetEntityAttribute via addExtraConfig to exercise a
   * user-defined field_&lt;name&gt; attribute.
   */
  private int entityAttributeCount = 4;

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

  public boolean isRealEmma() {
    return realEmma;
  }

  public EmmaProvisionerTestConfigInput assignRealEmma(boolean realEmma) {
    this.realEmma = realEmma;
    return this;
  }

  public String getGroupNameMapping() {
    return groupNameMapping;
  }

  public EmmaProvisionerTestConfigInput assignGroupNameMapping(String groupNameMapping) {
    this.groupNameMapping = groupNameMapping;
    return this;
  }

  public int getGroupAttributeCount() {
    return groupAttributeCount;
  }

  public EmmaProvisionerTestConfigInput assignGroupAttributeCount(int groupAttributeCount) {
    this.groupAttributeCount = groupAttributeCount;
    return this;
  }

  public int getEntityAttributeCount() {
    return entityAttributeCount;
  }

  public EmmaProvisionerTestConfigInput assignEntityAttributeCount(int entityAttributeCount) {
    this.entityAttributeCount = entityAttributeCount;
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
