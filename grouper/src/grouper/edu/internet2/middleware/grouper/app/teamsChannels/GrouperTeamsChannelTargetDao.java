package edu.internet2.middleware.grouper.app.teamsChannels;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import edu.internet2.middleware.grouper.app.provisioning.GrouperProvisioningConfigurationAttribute;
import edu.internet2.middleware.grouper.app.provisioning.ProvisioningEntity;
import edu.internet2.middleware.grouper.app.provisioning.ProvisioningGroup;
import edu.internet2.middleware.grouper.app.provisioning.ProvisioningMembership;
import edu.internet2.middleware.grouper.app.provisioning.ProvisioningObjectChange;
import edu.internet2.middleware.grouper.app.provisioning.ProvisioningObjectChangeAction;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.GrouperProvisionerDaoCapabilities;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.GrouperProvisionerTargetDaoBase;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.TargetDaoDeleteGroupsRequest;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.TargetDaoDeleteGroupsResponse;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.TargetDaoDeleteMembershipsRequest;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.TargetDaoDeleteMembershipsResponse;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.TargetDaoInsertGroupsRequest;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.TargetDaoInsertGroupsResponse;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.TargetDaoInsertMembershipsRequest;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.TargetDaoInsertMembershipsResponse;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.TargetDaoRetrieveAllEntitiesRequest;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.TargetDaoRetrieveAllEntitiesResponse;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.TargetDaoRetrieveAllGroupsRequest;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.TargetDaoRetrieveAllGroupsResponse;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.TargetDaoRetrieveEntitiesRequest;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.TargetDaoRetrieveEntitiesResponse;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.TargetDaoRetrieveGroupsRequest;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.TargetDaoRetrieveGroupsResponse;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.TargetDaoRetrieveMembershipsByGroupRequest;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.TargetDaoRetrieveMembershipsByGroupResponse;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.TargetDaoTimingInfo;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.TargetDaoUpdateGroupsRequest;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.TargetDaoUpdateGroupsResponse;
import edu.internet2.middleware.grouper.util.GrouperHttpClient;
import edu.internet2.middleware.grouper.util.GrouperHttpClientLog;
import edu.internet2.middleware.grouper.util.GrouperUtil;
import edu.internet2.middleware.grouperClient.collections.MultiKey;
import edu.internet2.middleware.grouperClient.util.ExpirableCache;

/**
 * DAO that adapts the Grouper provisioning framework to the Teams channel Graph
 * API.  Supports channel retrieve/insert/update/delete and membership
 * retrieve/insert/delete, plus read-only entity (user) resolution.
 *
 * Entity insert/update/delete is intentionally not implemented - this
 * provisioner operates on existing Entra users only.  Entities are readable
 * because channel memberships are keyed by the Entra user id (GUID), so the
 * framework must be able to resolve a Grouper subject to that id.
 *
 * Modeled on GrouperAzureTargetDao.
 */
public class GrouperTeamsChannelTargetDao extends GrouperProvisionerTargetDaoBase {

  /**
   * cache of (channelId, userId) to conversationMember id, populated during
   * membership retrieval and used to remove members (Teams requires the
   * conversationMember id, not the user id, in order to delete a member).
   */
  private static ExpirableCache<MultiKey, String> channelUserToMembershipId = new ExpirableCache<MultiKey, String>(60);

  @Override
  public boolean loggingStart() {
    return GrouperHttpClient.logStart(new GrouperHttpClientLog());
  }

  @Override
  public String loggingStop() {
    return GrouperHttpClient.logEnd();
  }

  private GrouperTeamsChannelConfiguration config() {
    return (GrouperTeamsChannelConfiguration) this.getGrouperProvisioner().retrieveGrouperProvisioningConfiguration();
  }

  private Set<String> extensionAttributeNames() {
    Map<String, GrouperProvisioningConfigurationAttribute> targetGroupAttributeNameToConfig = config().getTargetGroupAttributeNameToConfig();
    Set<String> extensionAttributeNames = new HashSet<String>();
    for (String key : GrouperUtil.nonNull(targetGroupAttributeNameToConfig).keySet()) {
      if (StringUtils.startsWith(key, "extension_")) {
        extensionAttributeNames.add(key);
      }
    }
    return extensionAttributeNames;
  }

  // ==================================================================
  // channel retrieve
  // ==================================================================

  @Override
  public TargetDaoRetrieveAllGroupsResponse retrieveAllGroups(TargetDaoRetrieveAllGroupsRequest targetDaoRetrieveAllGroupsRequest) {

    long startNanos = System.nanoTime();
    try {
      // channels are team-scoped, so "all channels" means all channels in the
      // teams that the Grouper groups reference.  Gather those team ids from the
      // grouper-side (translated) target groups.
      Set<String> teamIds = teamIdsFromProvisioningData();

      List<ProvisioningGroup> results = new ArrayList<ProvisioningGroup>();

      List<GrouperTeamsChannel> channels = GrouperTeamsChannelApiCommands.retrieveTeamsChannels(
          config().getTeamsExternalSystemConfigId(), teamIds, extensionAttributeNames());

      for (GrouperTeamsChannel channel : channels) {
        results.add(channel.toProvisioningGroup());
      }

      return new TargetDaoRetrieveAllGroupsResponse(results);
    } finally {
      this.addTargetDaoTimingInfo(new TargetDaoTimingInfo("retrieveAllGroups", startNanos));
    }
  }

  @Override
  public TargetDaoRetrieveGroupsResponse retrieveGroups(TargetDaoRetrieveGroupsRequest targetDaoRetrieveGroupsRequest) {

    long startNanos = System.nanoTime();
    try {
      List<ProvisioningGroup> grouperTargetGroups = targetDaoRetrieveGroupsRequest.getTargetGroups();
      String searchAttribute = targetDaoRetrieveGroupsRequest.getSearchAttribute();

      List<ProvisioningGroup> targetGroupsFromTeams = new ArrayList<ProvisioningGroup>();

      if (StringUtils.equals(searchAttribute, "id")) {
        // look up by (teamId, channel id)
        List<MultiKey> teamIdChannelIds = new ArrayList<MultiKey>();
        for (ProvisioningGroup targetGroup : GrouperUtil.nonNull(grouperTargetGroups)) {
          String teamId = targetGroup.retrieveAttributeValueString("teamId");
          String channelId = targetGroup.retrieveAttributeValueString("id");
          if (StringUtils.isBlank(channelId)) {
            channelId = targetGroup.getId();
          }
          if (StringUtils.isNotBlank(teamId) && StringUtils.isNotBlank(channelId)) {
            teamIdChannelIds.add(new MultiKey(teamId, channelId));
          }
        }
        List<GrouperTeamsChannel> channels = GrouperTeamsChannelApiCommands.retrieveTeamsChannelsByIds(
            config().getTeamsExternalSystemConfigId(), teamIdChannelIds, extensionAttributeNames());
        for (GrouperTeamsChannel channel : channels) {
          targetGroupsFromTeams.add(channel.toProvisioningGroup());
        }
      } else {
        // look up by displayName within each team
        for (ProvisioningGroup targetGroup : GrouperUtil.nonNull(grouperTargetGroups)) {
          String teamId = targetGroup.retrieveAttributeValueString("teamId");
          String displayName = targetGroup.retrieveAttributeValueString(searchAttribute);
          if (StringUtils.isBlank(teamId) || StringUtils.isBlank(displayName)) {
            continue;
          }
          GrouperTeamsChannel channel = GrouperTeamsChannelApiCommands.retrieveTeamsChannelByDisplayName(
              config().getTeamsExternalSystemConfigId(), teamId, displayName);
          if (channel != null) {
            targetGroupsFromTeams.add(channel.toProvisioningGroup());
          }
        }
      }

      TargetDaoRetrieveGroupsResponse response = new TargetDaoRetrieveGroupsResponse();
      response.setTargetGroups(targetGroupsFromTeams);
      return response;
    } finally {
      this.addTargetDaoTimingInfo(new TargetDaoTimingInfo("retrieveGroups", startNanos));
    }
  }

  // ==================================================================
  // channel insert / update / delete
  // ==================================================================

  @Override
  public TargetDaoInsertGroupsResponse insertGroups(TargetDaoInsertGroupsRequest targetDaoInsertGroupsRequest) {

    long startNanos = System.nanoTime();
    List<ProvisioningGroup> targetGroups = targetDaoInsertGroupsRequest.getTargetGroups();

    try {
      Map<GrouperTeamsChannel, ProvisioningGroup> channelToTargetGroup = new HashMap<>();
      Map<GrouperTeamsChannel, Set<String>> channelToFieldNamesToInsert = new HashMap<>();

      for (ProvisioningGroup targetGroup : GrouperUtil.nonNull(targetGroups)) {
        GrouperTeamsChannel channel = GrouperTeamsChannel.fromProvisioningGroup(targetGroup, null);
        channelToTargetGroup.put(channel, targetGroup);

        Set<String> fieldNamesToInsert = new HashSet<String>();
        fieldNamesToInsert.add("displayName");
        for (ProvisioningObjectChange provisioningObjectChange : GrouperUtil.nonNull(targetGroup.getInternal_objectChanges())) {
          if (provisioningObjectChange.getProvisioningObjectChangeAction() == ProvisioningObjectChangeAction.insert) {
            fieldNamesToInsert.add(provisioningObjectChange.getAttributeName());
          }
        }
        // membershipType must be present at create time to make a private/shared channel
        fieldNamesToInsert.add("membershipType");
        channelToFieldNamesToInsert.put(channel, fieldNamesToInsert);
      }

      Map<GrouperTeamsChannel, Exception> channelToMaybeException = GrouperTeamsChannelApiCommands.createTeamsChannels(
          config().getTeamsExternalSystemConfigId(), channelToFieldNamesToInsert);

      for (GrouperTeamsChannel channel : channelToMaybeException.keySet()) {
        Exception exception = channelToMaybeException.get(channel);
        ProvisioningGroup targetGroup = channelToTargetGroup.get(channel);
        if (exception == null) {
          targetGroup.setId(channel.getId());
          markProvisioned(targetGroup, true, null);
        } else {
          markProvisioned(targetGroup, false, exception);
        }
      }

      return new TargetDaoInsertGroupsResponse();
    } catch (Exception e) {
      markProvisioned(targetGroups, false, e);
      throw new RuntimeException("Failed to insert Teams channels", e);
    } finally {
      this.addTargetDaoTimingInfo(new TargetDaoTimingInfo("insertGroups", startNanos));
    }
  }

  @Override
  public TargetDaoUpdateGroupsResponse updateGroups(TargetDaoUpdateGroupsRequest targetDaoUpdateGroupsRequest) {

    long startNanos = System.nanoTime();
    List<ProvisioningGroup> targetGroups = targetDaoUpdateGroupsRequest.getTargetGroups();

    try {
      Map<GrouperTeamsChannel, ProvisioningGroup> channelToTargetGroup = new HashMap<>();
      Map<GrouperTeamsChannel, Set<String>> channelToFieldNamesToUpdate = new HashMap<>();

      for (ProvisioningGroup targetGroup : GrouperUtil.nonNull(targetGroups)) {
        GrouperTeamsChannel channel = GrouperTeamsChannel.fromProvisioningGroup(targetGroup, null);
        if (StringUtils.isBlank(channel.getId()) || StringUtils.isBlank(channel.getTeamId())) {
          continue;
        }
        channelToTargetGroup.put(channel, targetGroup);

        Set<String> fieldNamesToUpdate = new HashSet<String>();
        for (ProvisioningObjectChange provisioningObjectChange : GrouperUtil.nonNull(targetGroup.getInternal_objectChanges())) {
          String fieldName = provisioningObjectChange.getAttributeName();
          // membershipType and teamId are immutable on the target; never PATCH them
          if (StringUtils.equalsAny(fieldName, "membershipType", "teamId", "id")) {
            continue;
          }
          fieldNamesToUpdate.add(fieldName);
        }
        channelToFieldNamesToUpdate.put(channel, fieldNamesToUpdate);
      }

      Map<GrouperTeamsChannel, Exception> channelToMaybeException = GrouperTeamsChannelApiCommands.updateTeamsChannels(
          config().getTeamsExternalSystemConfigId(), channelToFieldNamesToUpdate);

      for (GrouperTeamsChannel channel : channelToMaybeException.keySet()) {
        Exception exception = channelToMaybeException.get(channel);
        ProvisioningGroup targetGroup = channelToTargetGroup.get(channel);
        markProvisioned(targetGroup, exception == null, exception);
      }

      return new TargetDaoUpdateGroupsResponse();
    } catch (Exception e) {
      markProvisioned(targetGroups, false, e);
      throw new RuntimeException("Failed to update Teams channels", e);
    } finally {
      this.addTargetDaoTimingInfo(new TargetDaoTimingInfo("updateGroups", startNanos));
    }
  }

  @Override
  public TargetDaoDeleteGroupsResponse deleteGroups(TargetDaoDeleteGroupsRequest targetDaoDeleteGroupsRequest) {

    long startNanos = System.nanoTime();
    List<ProvisioningGroup> targetGroups = targetDaoDeleteGroupsRequest.getTargetGroups();

    try {
      List<GrouperTeamsChannel> channels = new ArrayList<>();
      Map<GrouperTeamsChannel, ProvisioningGroup> channelToTargetGroup = new HashMap<>();

      for (ProvisioningGroup targetGroup : GrouperUtil.nonNull(targetGroups)) {
        GrouperTeamsChannel channel = GrouperTeamsChannel.fromProvisioningGroup(targetGroup, null);
        if (StringUtils.isBlank(channel.getId()) || StringUtils.isBlank(channel.getTeamId())) {
          continue;
        }
        channels.add(channel);
        channelToTargetGroup.put(channel, targetGroup);
      }

      Map<GrouperTeamsChannel, Exception> channelToMaybeException = GrouperTeamsChannelApiCommands.deleteTeamsChannels(
          config().getTeamsExternalSystemConfigId(), channels);

      for (GrouperTeamsChannel channel : channelToMaybeException.keySet()) {
        Exception exception = channelToMaybeException.get(channel);
        ProvisioningGroup targetGroup = channelToTargetGroup.get(channel);
        markProvisioned(targetGroup, exception == null, exception);
      }

      return new TargetDaoDeleteGroupsResponse();
    } catch (Exception e) {
      markProvisioned(targetGroups, false, e);
      throw new RuntimeException("Failed to delete Teams channels", e);
    } finally {
      this.addTargetDaoTimingInfo(new TargetDaoTimingInfo("deleteGroups", startNanos));
    }
  }

  // ==================================================================
  // entity (user) retrieve - READ ONLY
  //
  // Entities are resolved, never written.  A Teams channel membership is keyed
  // by the Entra user id (GUID), but Grouper subjects normally carry a netid /
  // UPN, so the framework needs to be able to look a user up in order to match
  // and provision memberships.  Insert/update/delete of entities remain
  // unimplemented on purpose.
  // ==================================================================

  @Override
  public TargetDaoRetrieveAllEntitiesResponse retrieveAllEntities(
      TargetDaoRetrieveAllEntitiesRequest targetDaoRetrieveAllEntitiesRequest) {

    long startNanos = System.nanoTime();

    try {
      List<ProvisioningEntity> results = new ArrayList<ProvisioningEntity>();
      Map<ProvisioningEntity, Object> targetEntityToNativeEntity = new HashMap<ProvisioningEntity, Object>();

      List<GrouperTeamsChannelUser> users = GrouperTeamsChannelApiCommands.retrieveTeamsChannelUsers(
          config().getTeamsExternalSystemConfigId());

      for (GrouperTeamsChannelUser user : GrouperUtil.nonNull(users)) {
        ProvisioningEntity targetEntity = user.toProvisioningEntity();
        results.add(targetEntity);
        targetEntityToNativeEntity.put(targetEntity, user);
      }

      TargetDaoRetrieveAllEntitiesResponse response = new TargetDaoRetrieveAllEntitiesResponse(results);

      if (targetDaoRetrieveAllEntitiesRequest != null && targetDaoRetrieveAllEntitiesRequest.isIncludeNativeEntity()) {
        response.setTargetEntityToTargetNativeEntity(targetEntityToNativeEntity);
      }

      return response;
    } finally {
      this.addTargetDaoTimingInfo(new TargetDaoTimingInfo("retrieveAllEntities", startNanos));
    }
  }

  @Override
  public TargetDaoRetrieveEntitiesResponse retrieveEntities(TargetDaoRetrieveEntitiesRequest targetDaoRetrieveEntitiesRequest) {

    long startNanos = System.nanoTime();

    try {
      List<ProvisioningEntity> targetEntities = targetDaoRetrieveEntitiesRequest.getTargetEntities();
      String searchAttribute = targetDaoRetrieveEntitiesRequest.getSearchAttribute();

      List<String> fieldValues = new ArrayList<String>();
      for (ProvisioningEntity targetEntity : GrouperUtil.nonNull(targetEntities)) {
        String attributeValue = null;
        if (StringUtils.equals(searchAttribute, "id")) {
          // the entity id may be carried on the object itself rather than as an attribute
          attributeValue = targetEntity.getId();
        }
        if (StringUtils.isBlank(attributeValue)) {
          attributeValue = targetEntity.retrieveAttributeValueString(searchAttribute);
        }
        if (StringUtils.isNotBlank(attributeValue)) {
          fieldValues.add(attributeValue);
        }
      }

      List<ProvisioningEntity> targetEntitiesFromTeams = new ArrayList<ProvisioningEntity>();
      Map<ProvisioningEntity, Object> targetEntityToNativeEntity = new HashMap<ProvisioningEntity, Object>();

      if (fieldValues.size() > 0) {
        List<GrouperTeamsChannelUser> users = GrouperTeamsChannelApiCommands.retrieveTeamsChannelUsers(
            config().getTeamsExternalSystemConfigId(), fieldValues, searchAttribute);

        for (GrouperTeamsChannelUser user : GrouperUtil.nonNull(users)) {
          ProvisioningEntity targetEntity = user.toProvisioningEntity();
          targetEntitiesFromTeams.add(targetEntity);
          targetEntityToNativeEntity.put(targetEntity, user);
        }
      }

      TargetDaoRetrieveEntitiesResponse response = new TargetDaoRetrieveEntitiesResponse();
      response.setTargetEntities(targetEntitiesFromTeams);

      if (targetDaoRetrieveEntitiesRequest.isIncludeNativeEntity()) {
        response.setTargetEntityToTargetNativeEntity(targetEntityToNativeEntity);
      }

      return response;
    } finally {
      this.addTargetDaoTimingInfo(new TargetDaoTimingInfo("retrieveEntities", startNanos));
    }
  }

  // ==================================================================
  // membership retrieve / insert / delete
  // ==================================================================

  @Override
  public TargetDaoRetrieveMembershipsByGroupResponse retrieveMembershipsByGroup(
      TargetDaoRetrieveMembershipsByGroupRequest targetDaoRetrieveMembershipsByGroupRequest) {

    long startNanos = System.nanoTime();
    ProvisioningGroup targetGroup = targetDaoRetrieveMembershipsByGroupRequest.getTargetGroup();

    List<ProvisioningMembership> provisioningMemberships = new ArrayList<ProvisioningMembership>();

    try {
      String teamId = targetGroup.retrieveAttributeValueString("teamId");
      String channelId = resolveTargetGroupId(targetGroup);

      if (StringUtils.isBlank(teamId) || StringUtils.isBlank(channelId)) {
        return new TargetDaoRetrieveMembershipsByGroupResponse(provisioningMemberships);
      }

      List<GrouperTeamsChannelMembership> members = GrouperTeamsChannelApiCommands.retrieveTeamsChannelMembers(
          config().getTeamsExternalSystemConfigId(), teamId, channelId);

      for (GrouperTeamsChannelMembership member : members) {
        // cache the conversationMember id so a later delete can use it
        channelUserToMembershipId.put(new MultiKey(channelId, member.getUserId()), member.getId());

        ProvisioningMembership targetMembership = new ProvisioningMembership(false);
        targetMembership.setProvisioningGroupId(targetGroup.getId());
        targetMembership.setProvisioningEntityId(member.getUserId());
        provisioningMemberships.add(targetMembership);
      }

      return new TargetDaoRetrieveMembershipsByGroupResponse(provisioningMemberships);
    } finally {
      this.addTargetDaoTimingInfo(new TargetDaoTimingInfo("retrieveMembershipsByGroup", startNanos));
    }
  }

  @Override
  public TargetDaoInsertMembershipsResponse insertMemberships(TargetDaoInsertMembershipsRequest targetDaoInsertMembershipsRequest) {

    long startNanos = System.nanoTime();
    List<ProvisioningMembership> targetMemberships = targetDaoInsertMembershipsRequest.getTargetMemberships();

    try {
      // collate by channel (group)
      Map<String, List<String>> channelIdToUserIds = new LinkedHashMap<String, List<String>>();
      Map<String, String> channelIdToTeamId = new HashMap<String, String>();
      Map<MultiKey, ProvisioningMembership> channelUserToMembership = new HashMap<>();

      for (ProvisioningMembership targetMembership : GrouperUtil.nonNull(targetMemberships)) {
        String channelId = targetMembership.getProvisioningGroupId();
        String userId = targetMembership.getProvisioningEntityId();

        channelUserToMembership.put(new MultiKey(channelId, userId), targetMembership);

        List<String> userIds = channelIdToUserIds.get(channelId);
        if (userIds == null) {
          userIds = new ArrayList<String>();
          channelIdToUserIds.put(channelId, userIds);
        }
        userIds.add(userId);

        // find the teamId for this channel from the corresponding target group
        if (!channelIdToTeamId.containsKey(channelId)) {
          channelIdToTeamId.put(channelId, resolveTeamIdForGroupId(channelId));
        }
      }

      for (String channelId : channelIdToUserIds.keySet()) {
        String teamId = channelIdToTeamId.get(channelId);
        List<String> userIds = channelIdToUserIds.get(channelId);

        if (StringUtils.isBlank(teamId)) {
          RuntimeException e = new RuntimeException("Cannot add channel members - unknown teamId for channel " + channelId);
          for (String userId : userIds) {
            markMembershipProvisioned(channelUserToMembership.get(new MultiKey(channelId, userId)), false, e);
          }
          continue;
        }

        Map<String, Object> userIdToResult = GrouperTeamsChannelApiCommands.createTeamsChannelMemberships(
            config().getTeamsExternalSystemConfigId(), teamId, channelId, userIds);

        for (String userId : userIds) {
          ProvisioningMembership targetMembership = channelUserToMembership.get(new MultiKey(channelId, userId));
          Object result = userIdToResult.get(userId);
          if (result instanceof Exception) {
            markMembershipProvisioned(targetMembership, false, (Exception) result);
          } else {
            // cache the returned conversationMember id for later delete
            if (result instanceof String && StringUtils.isNotBlank((String) result)) {
              channelUserToMembershipId.put(new MultiKey(channelId, userId), (String) result);
            }
            markMembershipProvisioned(targetMembership, true, null);
          }
        }
      }

      return new TargetDaoInsertMembershipsResponse();
    } finally {
      this.addTargetDaoTimingInfo(new TargetDaoTimingInfo("insertMemberships", startNanos));
    }
  }

  @Override
  public TargetDaoDeleteMembershipsResponse deleteMemberships(TargetDaoDeleteMembershipsRequest targetDaoDeleteMembershipsRequest) {

    long startNanos = System.nanoTime();
    List<ProvisioningMembership> targetMemberships = targetDaoDeleteMembershipsRequest.getTargetMemberships();

    try {
      // collate by channel
      Map<String, Map<String, String>> channelIdToUserIdToMembershipId = new LinkedHashMap<String, Map<String, String>>();
      Map<String, String> channelIdToTeamId = new HashMap<String, String>();
      Map<MultiKey, ProvisioningMembership> channelUserToMembership = new HashMap<>();

      for (ProvisioningMembership targetMembership : GrouperUtil.nonNull(targetMemberships)) {
        String channelId = targetMembership.getProvisioningGroupId();
        String userId = targetMembership.getProvisioningEntityId();

        channelUserToMembership.put(new MultiKey(channelId, userId), targetMembership);

        Map<String, String> userIdToMembershipId = channelIdToUserIdToMembershipId.get(channelId);
        if (userIdToMembershipId == null) {
          userIdToMembershipId = new LinkedHashMap<String, String>();
          channelIdToUserIdToMembershipId.put(channelId, userIdToMembershipId);
        }
        // use cached conversationMember id if present; else null triggers a lookup
        String cachedMembershipId = channelUserToMembershipId.get(new MultiKey(channelId, userId));
        userIdToMembershipId.put(userId, cachedMembershipId);

        if (!channelIdToTeamId.containsKey(channelId)) {
          channelIdToTeamId.put(channelId, resolveTeamIdForGroupId(channelId));
        }
      }

      for (String channelId : channelIdToUserIdToMembershipId.keySet()) {
        String teamId = channelIdToTeamId.get(channelId);
        Map<String, String> userIdToMembershipId = channelIdToUserIdToMembershipId.get(channelId);

        if (StringUtils.isBlank(teamId)) {
          RuntimeException e = new RuntimeException("Cannot remove channel members - unknown teamId for channel " + channelId);
          for (String userId : userIdToMembershipId.keySet()) {
            markMembershipProvisioned(channelUserToMembership.get(new MultiKey(channelId, userId)), false, e);
          }
          continue;
        }

        Map<String, Exception> userIdToException = GrouperTeamsChannelApiCommands.deleteTeamsChannelMemberships(
            config().getTeamsExternalSystemConfigId(), teamId, channelId, userIdToMembershipId);

        for (String userId : userIdToException.keySet()) {
          ProvisioningMembership targetMembership = channelUserToMembership.get(new MultiKey(channelId, userId));
          Exception exception = userIdToException.get(userId);
          if (exception == null) {
            channelUserToMembershipId.remove(new MultiKey(channelId, userId));
          }
          markMembershipProvisioned(targetMembership, exception == null, exception);
        }
      }

      return new TargetDaoDeleteMembershipsResponse();
    } catch (Exception e) {
      for (ProvisioningMembership targetMembership : GrouperUtil.nonNull(targetMemberships)) {
        markMembershipProvisioned(targetMembership, false, e);
      }
      throw new RuntimeException("Failed to delete Teams channel memberships", e);
    } finally {
      this.addTargetDaoTimingInfo(new TargetDaoTimingInfo("deleteMemberships", startNanos));
    }
  }

  // ==================================================================
  // helpers
  // ==================================================================

  /**
   * resolve the target channel id for a group, looking it up by displayName if
   * it isn't already set.
   */
  public String resolveTargetGroupId(ProvisioningGroup targetGroup) {
    if (targetGroup == null) {
      return null;
    }
    if (StringUtils.isNotBlank(targetGroup.getId())) {
      return targetGroup.getId();
    }
    String channelId = targetGroup.retrieveAttributeValueString("id");
    if (StringUtils.isNotBlank(channelId)) {
      return channelId;
    }

    TargetDaoRetrieveGroupsRequest request = new TargetDaoRetrieveGroupsRequest();
    request.setTargetGroups(GrouperUtil.toList(targetGroup));
    request.setIncludeAllMembershipsIfApplicable(false);
    TargetDaoRetrieveGroupsResponse response = this.getGrouperProvisioner()
        .retrieveGrouperProvisioningTargetDaoAdapter().retrieveGroups(request);

    if (response == null || GrouperUtil.length(response.getTargetGroups()) == 0) {
      return null;
    }
    return response.getTargetGroups().get(0).getId();
  }

  /**
   * find the teamId that owns a given target channel id by consulting the
   * translated grouper-target provisioning groups (which carry the teamId).
   */
  private String resolveTeamIdForGroupId(String channelId) {
    for (ProvisioningGroup grouperTargetGroup : grouperTargetGroups()) {
      if (StringUtils.equals(grouperTargetGroup.getId(), channelId)) {
        return grouperTargetGroup.retrieveAttributeValueString("teamId");
      }
    }
    return null;
  }

  /**
   * the set of parent team ids referenced by the grouper-target groups.
   */
  private Set<String> teamIdsFromProvisioningData() {
    Set<String> teamIds = new HashSet<String>();
    for (ProvisioningGroup grouperTargetGroup : grouperTargetGroups()) {
      String teamId = grouperTargetGroup.retrieveAttributeValueString("teamId");
      if (StringUtils.isNotBlank(teamId)) {
        teamIds.add(teamId);
      }
    }
    return teamIds;
  }

  /**
   * the translated grouper-side target groups for this provisioning run.
   */
  private List<ProvisioningGroup> grouperTargetGroups() {
    return GrouperUtil.nonNull(
        this.getGrouperProvisioner().retrieveGrouperProvisioningData().retrieveTargetProvisioningGroups());
  }

  private void markProvisioned(List<ProvisioningGroup> targetGroups, boolean provisioned, Exception exception) {
    for (ProvisioningGroup targetGroup : GrouperUtil.nonNull(targetGroups)) {
      markProvisioned(targetGroup, provisioned, exception);
    }
  }

  private void markProvisioned(ProvisioningGroup targetGroup, boolean provisioned, Exception exception) {
    if (targetGroup == null) {
      return;
    }
    targetGroup.setProvisioned(provisioned);
    if (exception != null) {
      targetGroup.setException(exception);
    }
    for (ProvisioningObjectChange provisioningObjectChange : GrouperUtil.nonNull(targetGroup.getInternal_objectChanges())) {
      provisioningObjectChange.setProvisioned(provisioned);
    }
  }

  private void markMembershipProvisioned(ProvisioningMembership targetMembership, boolean provisioned, Exception exception) {
    if (targetMembership == null) {
      return;
    }
    targetMembership.setProvisioned(provisioned);
    if (exception != null) {
      targetMembership.setException(exception);
    }
    for (ProvisioningObjectChange provisioningObjectChange : GrouperUtil.nonNull(targetMembership.getInternal_objectChanges())) {
      provisioningObjectChange.setProvisioned(provisioned);
    }
  }

  @Override
  public void registerGrouperProvisionerDaoCapabilities(GrouperProvisionerDaoCapabilities grouperProvisionerDaoCapabilities) {

    grouperProvisionerDaoCapabilities.setDefaultBatchSize(20);

    // channels
    grouperProvisionerDaoCapabilities.setCanInsertGroups(true);
    grouperProvisionerDaoCapabilities.setCanUpdateGroups(true);
    grouperProvisionerDaoCapabilities.setCanDeleteGroups(true);
    grouperProvisionerDaoCapabilities.setCanRetrieveAllGroups(true);
    grouperProvisionerDaoCapabilities.setCanRetrieveGroups(true);

    // memberships
    grouperProvisionerDaoCapabilities.setCanInsertMemberships(true);
    grouperProvisionerDaoCapabilities.setCanDeleteMemberships(true);
    grouperProvisionerDaoCapabilities.setCanRetrieveMembershipsAllByGroup(true);

    // entities - RESOLVE ONLY.  Memberships are keyed by the Entra user id, so
    // the framework must be able to look users up.  Insert/update/delete of
    // entities remain unsupported on purpose.
    grouperProvisionerDaoCapabilities.setCanRetrieveEntities(true);
    grouperProvisionerDaoCapabilities.setCanRetrieveAllEntities(true);
  }

}