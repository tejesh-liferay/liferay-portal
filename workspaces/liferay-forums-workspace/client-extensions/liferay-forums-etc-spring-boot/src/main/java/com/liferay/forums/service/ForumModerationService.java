/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.forums.service;

import com.liferay.forums.client.LiferayApiClient;
import com.liferay.petra.string.StringBundler;

import java.net.URLEncoder;

import java.nio.charset.StandardCharsets;

import java.util.HashSet;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * @author Roselaine Marques
 */
@Service
public class ForumModerationService {

	public boolean canAddForumBan(long userId, String authToken) {
		if (userId <= 0) {
			return false;
		}

		Set<String> roleNames = _fetchRoleNames(userId, authToken);

		if (roleNames.contains(_ADMINISTRATOR_ROLE_NAME)) {
			return true;
		}

		for (String roleName : _fetchForumBanRoleNames(authToken)) {
			if (roleNames.contains(roleName)) {
				return true;
			}
		}

		return false;
	}

	public boolean canManageForumSuspiciousActivity(
		long entryId, String actorAuthToken, String authToken) {

		if (entryId <= 0) {
			return false;
		}

		long siteId = resolveSiteId(authToken);

		if (siteId <= 0) {
			return false;
		}

		try {

			// updateBatch/deleteBatch on the collection response reflect the
			// caller's resource level (company/group scope) permission, not
			// per entry owner permission, so this is authenticated as the
			// real acting user (actorAuthToken), never the service account.

			JSONObject pageJSONObject = new JSONObject(
				_liferayApiClient.get(
					StringBundler.concat(
						"/o/c/forumsuspiciousactivities/scopes/", siteId,
						"?pageSize=1&filter=",
						_encode("id eq '" + entryId + "'")),
					actorAuthToken));

			JSONObject actionsJSONObject = pageJSONObject.optJSONObject(
				"actions");

			boolean canManage = false;

			if ((actionsJSONObject != null) &&
				(actionsJSONObject.has("updateBatch") ||
				 actionsJSONObject.has("deleteBatch"))) {

				canManage = true;
			}

			// TEMPORARY: remove once permission resolution is verified live

			if (_log.isInfoEnabled()) {
				_log.info(
					StringBundler.concat(
						"[DIAG] canManageForumSuspiciousActivity entryId=",
						entryId, " actions=", actionsJSONObject, " canManage=",
						canManage));
			}

			return canManage;
		}
		catch (Exception exception) {
			_log.error(
				StringBundler.concat(
					"Unable to check the manage permission for suspicious ",
					"activity ", entryId, ": ", exception.getMessage()));

			return false;
		}
	}

	public void cascadeValidateSuspiciousActivities(
		String threadERC, long validatedObjectEntryId, String authToken) {

		if ((threadERC == null) || threadERC.isEmpty()) {
			return;
		}

		long siteId = resolveSiteId(authToken);

		if (siteId <= 0) {
			_log.error(
				StringBundler.concat(
					"Unable to cascade the validation of suspicious ",
					"activities for thread ", threadERC,
					" without a site scope"));

			return;
		}

		try {

			// Relationship fields must be filtered by the related entry's ERC,
			// not its numeric ID, or the OData parser throws "Incompatible
			// types."

			String filter = StringBundler.concat(
				"r_threadSuspiciousActivities_c_forumThreadERC eq '", threadERC,
				"' and validated eq false");

			JSONArray itemsJSONArray = new JSONObject(
				_liferayApiClient.get(
					StringBundler.concat(
						"/o/c/forumsuspiciousactivities/scopes/", siteId,
						"?fields=id&pageSize=-1&filter=", _encode(filter)),
					authToken)
			).optJSONArray(
				"items"
			);

			if (itemsJSONArray == null) {
				return;
			}

			JSONObject validatedJSONObject = new JSONObject(
			).put(
				"validated", true
			);

			for (int i = 0; i < itemsJSONArray.length(); i++) {
				JSONObject itemJSONObject = itemsJSONArray.optJSONObject(i);

				if (itemJSONObject == null) {
					continue;
				}

				long entryId = itemJSONObject.optLong("id", 0L);

				if ((entryId <= 0) || (entryId == validatedObjectEntryId)) {
					continue;
				}

				_liferayApiClient.patch(
					"/o/c/forumsuspiciousactivities/" + entryId, authToken,
					validatedJSONObject.toString());
			}
		}
		catch (Exception exception) {
			_log.error(
				StringBundler.concat(
					"Unable to cascade the validation of suspicious ",
					"activities for thread ", threadERC, ": ",
					exception.getMessage()));
		}
	}

	public boolean forumThreadExists(String threadERC, String authToken) {
		if ((threadERC == null) || threadERC.isEmpty()) {
			return false;
		}

		long siteId = resolveSiteId(authToken);

		if (siteId <= 0) {
			return false;
		}

		try {
			JSONObject threadJSONObject = new JSONObject(
				_liferayApiClient.get(
					StringBundler.concat(
						"/o/c/forumthreads/scopes/", siteId,
						"/by-external-reference-code/", _encode(threadERC),
						"?fields=id"),
					authToken));

			return threadJSONObject.optLong("id", 0L) > 0;
		}
		catch (Exception exception) {
			if (_log.isDebugEnabled()) {
				_log.debug(
					StringBundler.concat(
						"Unable to confirm thread ", threadERC,
						" exists: ", exception.getMessage()));
			}

			return false;
		}
	}

	public boolean isBanned(long userId, String authToken) {
		if (userId <= 0) {
			return false;
		}

		long siteId = resolveSiteId(authToken);

		if (siteId <= 0) {
			_log.error(
				"Unable to check the ban for user " + userId +
					" without a site scope");

			return true;
		}

		try {
			JSONArray itemsJSONArray = new JSONObject(
				_liferayApiClient.get(
					StringBundler.concat(
						"/o/c/forumbans/scopes/", siteId,
						"?fields=banUserId&pageSize=1&filter=",
						_encode("banUserId eq " + userId)),
					authToken)
			).optJSONArray(
				"items"
			);

			if ((itemsJSONArray != null) && !itemsJSONArray.isEmpty()) {
				return true;
			}

			return false;
		}
		catch (Exception exception) {
			_log.error(
				StringBundler.concat(
					"Unable to check the ban for user ", userId, ": ",
					exception.getMessage()));

			return true;
		}
	}

	public boolean isThreadLocked(long threadId, String authToken) {
		if (threadId <= 0) {
			return false;
		}

		try {
			JSONObject threadJSONObject = new JSONObject(
				_liferayApiClient.get(
					StringBundler.concat(
						"/o/c/forumthreads/", threadId, "?fields=locked"),
					authToken));

			return threadJSONObject.optBoolean("locked", false);
		}
		catch (Exception exception) {
			if (_log.isDebugEnabled()) {
				_log.debug(
					StringBundler.concat(
						"Unable to read the lock state of thread ", threadId,
						": ", exception.getMessage()));
			}

			return false;
		}
	}

	public boolean isThreadPriorityUnchanged(
		String externalReferenceCode, double priority, String authToken) {

		if ((externalReferenceCode == null) ||
			externalReferenceCode.isEmpty()) {

			return false;
		}

		long siteId = resolveSiteId(authToken);

		if (siteId <= 0) {
			return false;
		}

		try {
			JSONObject threadJSONObject = new JSONObject(
				_liferayApiClient.get(
					StringBundler.concat(
						"/o/c/forumthreads/scopes/", siteId,
						"/by-external-reference-code/",
						_encode(externalReferenceCode), "?fields=priority"),
					authToken));

			if (threadJSONObject.optDouble("priority", -1) == priority) {
				return true;
			}

			return false;
		}
		catch (Exception exception) {
			if (_log.isDebugEnabled()) {
				_log.debug(
					StringBundler.concat(
						"Unable to read the stored priority of thread ",
						externalReferenceCode, ": ", exception.getMessage()));
			}

			return false;
		}
	}

	public void recreateForumSuspiciousActivity(
		String valuesJSON, String authToken) {

		long siteId = resolveSiteId(authToken);

		if (siteId <= 0) {
			_log.error(
				"Unable to recreate a deleted suspicious activity without a " +
					"site scope");

			return;
		}

		try {
			_liferayApiClient.post(
				"/o/c/forumsuspiciousactivities/scopes/" + siteId, authToken,
				valuesJSON);

			if (_log.isInfoEnabled()) {
				_log.info(
					"Recreated an unauthorized self deleted suspicious " +
						"activity");
			}
		}
		catch (Exception exception) {
			_log.error(
				"Unable to recreate a deleted suspicious activity: " +
					exception.getMessage());
		}
	}

	public long resolveSiteId(String authToken) {
		try {
			return new JSONObject(
				_liferayApiClient.get(
					StringBundler.concat(
						"/o/headless-admin-site/v1.0/sites/",
						_encode(_siteExternalReferenceCode), "?fields=id"),
					authToken)
			).optLong(
				"id", 0L
			);
		}
		catch (Exception exception) {
			_log.error(
				StringBundler.concat(
					"Unable to resolve the site ", _siteExternalReferenceCode,
					": ", exception.getMessage()));

			return 0L;
		}
	}

	public void revertForumSuspiciousActivity(
		long entryId, String originalValuesJSON, String authToken) {

		if (entryId <= 0) {
			return;
		}

		try {
			_liferayApiClient.patch(
				"/o/c/forumsuspiciousactivities/" + entryId, authToken,
				originalValuesJSON);

			if (_log.isInfoEnabled()) {
				_log.info(
					StringBundler.concat(
						"Reverted an unauthorized self edit on suspicious ",
						"activity ", entryId));
			}
		}
		catch (Exception exception) {
			_log.error(
				StringBundler.concat(
					"Unable to revert suspicious activity ", entryId, ": ",
					exception.getMessage()));
		}
	}

	private String _encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private Set<String> _fetchForumBanRoleNames(String authToken) {
		Set<String> roleNames = new HashSet<>();

		try {
			JSONArray permissionsJSONArray = new JSONObject(
				_liferayApiClient.get(
					"/o/object-admin/v1.0/object-definitions" +
						"/by-external-reference-code/FORUM-BAN" +
							"?fields=permissions",
					authToken)
			).optJSONArray(
				"permissions"
			);

			if (permissionsJSONArray == null) {
				return roleNames;
			}

			for (int i = 0; i < permissionsJSONArray.length(); i++) {
				JSONObject permissionJSONObject =
					permissionsJSONArray.optJSONObject(i);

				if (permissionJSONObject == null) {
					continue;
				}

				JSONArray actionIdsJSONArray =
					permissionJSONObject.optJSONArray("actionIds");

				if (actionIdsJSONArray == null) {
					continue;
				}

				for (int j = 0; j < actionIdsJSONArray.length(); j++) {
					if (_ADD_OBJECT_ENTRY_ACTION_ID.equals(
							actionIdsJSONArray.optString(j))) {

						roleNames.add(
							permissionJSONObject.optString("roleName", ""));

						break;
					}
				}
			}
		}
		catch (Exception exception) {
			_log.error(
				"Unable to read the roles that may add a forum ban: " +
					exception.getMessage());
		}

		return roleNames;
	}

	private Set<String> _fetchRoleNames(long userId, String authToken) {
		Set<String> roleNames = new HashSet<>();

		try {
			JSONArray roleBriefsJSONArray = new JSONObject(
				_liferayApiClient.get(
					StringBundler.concat(
						"/o/headless-admin-user/v1.0/user-accounts/", userId,
						"?fields=roleBriefs"),
					authToken)
			).optJSONArray(
				"roleBriefs"
			);

			if (roleBriefsJSONArray == null) {
				return roleNames;
			}

			for (int i = 0; i < roleBriefsJSONArray.length(); i++) {
				JSONObject roleBriefJSONObject =
					roleBriefsJSONArray.optJSONObject(i);

				if (roleBriefJSONObject != null) {
					roleNames.add(roleBriefJSONObject.optString("name", ""));
				}
			}
		}
		catch (Exception exception) {
			_log.error(
				StringBundler.concat(
					"Unable to read the roles of user ", userId, ": ",
					exception.getMessage()));
		}

		return roleNames;
	}

	private static final String _ADD_OBJECT_ENTRY_ACTION_ID =
		"ADD_OBJECT_ENTRY";

	private static final String _ADMINISTRATOR_ROLE_NAME = "Administrator";

	private static final Log _log = LogFactory.getLog(
		ForumModerationService.class);

	@Autowired
	private LiferayApiClient _liferayApiClient;

	@Value("${forums.site.external.reference.code:LIFERAY_FORUMS}")
	private String _siteExternalReferenceCode;

}