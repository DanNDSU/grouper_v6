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

  /**
   * Whether a reactivated agent should be restored as full-time. The Freshservice
   * /reactivate endpoint always restores an agent as occasional regardless of
   * their prior license type; when this is true, the provisioner follows the
   * reactivate with a PUT setting occasional=false to restore full-time.
   * Setting a full-time agent consumes a licensed seat, so sites that want
   * reactivated agents to remain occasional (day-pass) can set this to false.
   * Defaults to true.
   *
   * Config key: provisioner.&lt;configId&gt;.reactivateAsFullTime
   */
  private boolean reactivateAsFullTime = true;

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

    // optional: restore reactivated agents as full-time (defaults to true).
    // Only an explicit "false" turns this off; unset keeps the default. A value
    // that is neither "true" nor "false" is a configuration error rather than a
    // silent fallback, since silently choosing occasional/full-time affects seat
    // licensing.
    String reactivateAsFullTimeString = this.retrieveConfigString("reactivateAsFullTime", false);
    if (StringUtils.isNotBlank(reactivateAsFullTimeString)) {
      String trimmed = reactivateAsFullTimeString.trim();
      if ("true".equalsIgnoreCase(trimmed)) {
        this.reactivateAsFullTime = true;
      } else if ("false".equalsIgnoreCase(trimmed)) {
        this.reactivateAsFullTime = false;
      } else {
        throw new RuntimeException("Configuration 'reactivateAsFullTime' must be 'true' or 'false', but was: '"
            + reactivateAsFullTimeString + "'");
      }
    }
    
    System.out.println("FRESH_AGENT defaultAgentRoleId=" + this.defaultAgentRoleId
        + " scope=" + this.defaultAgentRoleAssignmentScope);
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

  /**
   * Whether reactivated agents should be restored as full-time (default true).
   * @return true to restore reactivated agents as full-time, false to leave them occasional
   */
  public boolean isReactivateAsFullTime() {
    return reactivateAsFullTime;
  }

  /**
   * Set whether reactivated agents should be restored as full-time.
   * @param reactivateAsFullTime true to restore full-time, false to leave occasional
   */
  public void setReactivateAsFullTime(boolean reactivateAsFullTime) {
    this.reactivateAsFullTime = reactivateAsFullTime;
  }

}
