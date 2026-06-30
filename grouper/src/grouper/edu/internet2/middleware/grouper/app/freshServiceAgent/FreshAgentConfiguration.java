package edu.internet2.middleware.grouper.app.freshServiceAgent;

import org.apache.commons.lang3.StringUtils;

import edu.internet2.middleware.grouper.app.provisioning.GrouperProvisioningConfiguration;

public class FreshAgentConfiguration extends GrouperProvisioningConfiguration {
  
  private String freshserviceExternalSystemConfigId;

  /**
   * Optional default Freshservice agent role id to assign to brand new agents
   * that do not already carry a roles array. Freshservice requires a non-empty
   * roles array on agent create, so configuring this lets the provisioner create
   * agents without the caller having to supply roles. Null/blank means no default
   * (the entity must then supply its own roles, or the create will fail with an
   * HTTP 400 from Freshservice).
   *
   * Config key: provisioner.&lt;configId&gt;.defaultAgentRoleId
   */
  private Long defaultAgentRoleId;

  /**
   * The assignment_scope paired with {@link #defaultAgentRoleId} when building the
   * default role object. Freshservice role entries require an assignment_scope
   * (e.g. "entire_helpdesk", "member_groups", "specified_groups"). Defaults to
   * "entire_helpdesk" when not configured.
   *
   * Config key: provisioner.&lt;configId&gt;.defaultAgentRoleAssignmentScope
   */
  private String defaultAgentRoleAssignmentScope;

  /** Default assignment_scope used when {@link #defaultAgentRoleId} is set but no scope is configured. */
  public static final String DEFAULT_ROLE_ASSIGNMENT_SCOPE = "entire_helpdesk";

  @Override
  public void configureSpecificSettings() {
    this.freshserviceExternalSystemConfigId = this.retrieveConfigString("freshserviceExternalSystemConfigId", true);

    // optional: default role id for new agents (role id only)
    String defaultAgentRoleIdString = this.retrieveConfigString("defaultAgentRoleId", false);
    if (StringUtils.isNotBlank(defaultAgentRoleIdString)) {
      try {
        this.defaultAgentRoleId = Long.valueOf(defaultAgentRoleIdString.trim());
      } catch (NumberFormatException nfe) {
        throw new RuntimeException("Configuration 'defaultAgentRoleId' must be a numeric Freshservice role id, but was: '"
            + defaultAgentRoleIdString + "'", nfe);
      }
    }

    // optional: assignment_scope for the default role (defaults to entire_helpdesk)
    String defaultAgentRoleAssignmentScopeString = this.retrieveConfigString("defaultAgentRoleAssignmentScope", false);
    this.defaultAgentRoleAssignmentScope = StringUtils.isBlank(defaultAgentRoleAssignmentScopeString)
        ? DEFAULT_ROLE_ASSIGNMENT_SCOPE : defaultAgentRoleAssignmentScopeString.trim();
  }
  
  /**
   * Get the FreshService external system config ID
   * @return the Freshservice external system config ID
   */
  public String getFreshserviceExternalSystemConfigId() {
    return freshserviceExternalSystemConfigId;
  }

  /**
   * Set the FreshService external system config ID
   * @param freshserviceExternalSystemConfigId the external system config ID to set
   */
  public void setFreshserviceExternalSystemConfigId(String freshserviceExternalSystemConfigId) {
    this.freshserviceExternalSystemConfigId = freshserviceExternalSystemConfigId;
  }

  /**
   * Get the optional default Freshservice agent role id for new agents.
   * @return the default role id, or null if not configured
   */
  public Long getDefaultAgentRoleId() {
    return defaultAgentRoleId;
  }

  /**
   * Set the default Freshservice agent role id for new agents.
   * @param defaultAgentRoleId the default role id
   */
  public void setDefaultAgentRoleId(Long defaultAgentRoleId) {
    this.defaultAgentRoleId = defaultAgentRoleId;
  }

  /**
   * Get the assignment_scope paired with the default agent role id.
   * @return the assignment scope (never null once configureSpecificSettings has run)
   */
  public String getDefaultAgentRoleAssignmentScope() {
    return defaultAgentRoleAssignmentScope;
  }

  /**
   * Set the assignment_scope paired with the default agent role id.
   * @param defaultAgentRoleAssignmentScope the assignment scope
   */
  public void setDefaultAgentRoleAssignmentScope(String defaultAgentRoleAssignmentScope) {
    this.defaultAgentRoleAssignmentScope = defaultAgentRoleAssignmentScope;
  }

}
