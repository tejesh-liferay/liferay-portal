/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.forums;

import com.liferay.client.extension.util.spring.boot3.BaseRestController;
import com.liferay.client.extension.util.spring.boot3.client.LiferayOAuth2AccessTokenManager;
import com.liferay.forums.service.ForumMessageService;
import com.liferay.forums.service.ForumModerationService;
import com.liferay.petra.string.StringBundler;

import java.math.BigDecimal;

import java.util.Objects;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author Roselaine Marques
 */
@RequestMapping("/object-validation-rule")
@RestController
public class ObjectValidationRuleRestController extends BaseRestController {

	@PostMapping("/ban")
	public ResponseEntity<String> ban(
		@AuthenticationPrincipal Jwt jwt, @RequestBody String json) {

		if (jwt != null) {
			log(jwt, _log, json);
		}

		JSONObject payloadJSONObject = new JSONObject(json);

		String authToken = _serviceAuthToken();

		long siteId = _resolveSiteIdByThread(payloadJSONObject, authToken);

		if (_isOwnFieldsOnlyUpdate(payloadJSONObject, siteId, authToken)) {
			return _respond(payloadJSONObject, true);
		}

		long creatorUserId = _resolveCreatorUserId(payloadJSONObject);

		boolean banned = _forumModerationService.isBanned(
			creatorUserId, siteId, authToken);

		if (banned && _log.isInfoEnabled()) {
			_log.info("Refused a post from banned user " + creatorUserId);
		}

		return _respond(payloadJSONObject, !banned);
	}

	@PostMapping("/locked")
	public ResponseEntity<String> locked(
		@AuthenticationPrincipal Jwt jwt, @RequestBody String json) {

		if (jwt != null) {
			log(jwt, _log, json);
		}

		JSONObject payloadJSONObject = new JSONObject(json);

		String authToken = _serviceAuthToken();

		if (_isOwnFieldsOnlyUpdate(
				payloadJSONObject,
				_resolveSiteIdByThread(payloadJSONObject, authToken),
				authToken)) {

			return _respond(payloadJSONObject, true);
		}

		long threadId = payloadJSONObject.optLong(
			"r_threadMessages_c_c2m0ThreadId");

		boolean locked = _forumModerationService.isThreadLocked(
			threadId, authToken);

		if (locked && _log.isInfoEnabled()) {
			_log.info("Refused a message on locked thread " + threadId);
		}

		return _respond(payloadJSONObject, !locked);
	}

	@PostMapping("/priority")
	public ResponseEntity<String> priority(
		@AuthenticationPrincipal Jwt jwt, @RequestBody String json) {

		if (jwt != null) {
			log(jwt, _log, json);
		}

		JSONObject payloadJSONObject = new JSONObject(json);

		double priority = payloadJSONObject.optDouble("priority", 0);

		if (priority <= 0) {
			return _respond(payloadJSONObject, true);
		}

		String authToken = _serviceAuthToken();

		long siteId = _resolveSiteIdByThread(payloadJSONObject, authToken);

		if (_isOwnFieldsOnlyUpdate(payloadJSONObject, siteId, authToken) ||
			_forumModerationService.isThreadPriorityUnchanged(
				payloadJSONObject.optString("externalReferenceCode"), priority,
				siteId, authToken)) {

			return _respond(payloadJSONObject, true);
		}

		long creatorUserId = _resolveCreatorUserId(payloadJSONObject);

		boolean allowed = _forumModerationService.canModerateForum(
			creatorUserId, authToken);

		if (!allowed && _log.isInfoEnabled()) {
			_log.info("Refused a thread priority set by user " + creatorUserId);
		}

		return _respond(payloadJSONObject, allowed);
	}

	@PostMapping("/tree-path")
	public ResponseEntity<String> treePath(
		@AuthenticationPrincipal Jwt jwt, @RequestBody String json) {

		if (jwt != null) {
			log(jwt, _log, json);
		}

		JSONObject payloadJSONObject = new JSONObject(json);

		// Messages written before treePath existed have none until they are
		// backfilled; forums-message-detail falls back to walking up their
		// parents, so an empty path is allowed rather than blocking edits

		String treePath = payloadJSONObject.optString("treePath", "");

		if (treePath.isEmpty()) {
			return _respond(payloadJSONObject, true);
		}

		String authToken = _serviceAuthToken();

		if (_isOwnFieldsOnlyUpdate(
				payloadJSONObject,
				_resolveSiteIdByThread(payloadJSONObject, authToken),
				authToken)) {

			return _respond(payloadJSONObject, true);
		}

		long parentMessageId = payloadJSONObject.optLong(
			"r_messageReplies_c_c2m0MessageId");

		boolean valid = _forumMessageService.isTreePathValid(
			treePath, parentMessageId, authToken);

		if (!valid && _log.isInfoEnabled()) {
			_log.info(
				StringBundler.concat(
					"Refused tree path \"", treePath, "\" under message ",
					parentMessageId));
		}

		return _respond(payloadJSONObject, valid);
	}

	private boolean _equals(Object value1, Object value2) {
		if ((value1 == null) || JSONObject.NULL.equals(value1)) {
			value1 = "";
		}

		if ((value2 == null) || JSONObject.NULL.equals(value2)) {
			value2 = "";
		}

		if ((value1 instanceof Number) && (value2 instanceof Number)) {
			BigDecimal bigDecimal1 = new BigDecimal(value1.toString());

			if (bigDecimal1.compareTo(new BigDecimal(value2.toString())) == 0) {
				return true;
			}

			return false;
		}

		if ((value1 instanceof JSONObject) && (value2 instanceof JSONObject)) {
			JSONObject jsonObject1 = (JSONObject)value1;

			return jsonObject1.similar(value2);
		}

		if ((value1 instanceof JSONArray) && (value2 instanceof JSONArray)) {
			JSONArray jsonArray1 = (JSONArray)value1;

			return jsonArray1.similar(value2);
		}

		return Objects.equals(String.valueOf(value1), String.valueOf(value2));
	}

	// Liferay calls object validation rules as the service account whoever
	// made the change, and sends only the entry, so a rule cannot tell a
	// user's write from this microservice's own. An update that changes only
	// fields this microservice owns (a thread's lastPostDate, a message's
	// notification fields) is its own and passes; a new entry, or an update
	// that changes anything else, is validated.

	private boolean _isOwnFieldsOnlyUpdate(
		JSONObject payloadJSONObject, long siteId, String authToken) {

		String restPath = "c2m0threads";

		if (payloadJSONObject.has("r_threadMessages_c_c2m0ThreadId")) {
			restPath = "c2m0messages";
		}

		JSONObject storedJSONObject = _forumModerationService.fetchStoredEntry(
			restPath, payloadJSONObject.optString("externalReferenceCode"),
			siteId, authToken);

		if (storedJSONObject == null) {
			return false;
		}

		for (String key : payloadJSONObject.keySet()) {
			if (_ownFieldNames.contains(key) ||
				_uncomparedFieldNames.contains(key)) {

				continue;
			}

			if (!_equals(
					payloadJSONObject.opt(key), storedJSONObject.opt(key))) {

				if (_log.isDebugEnabled()) {
					_log.debug(
						StringBundler.concat(
							"Validating ", restPath, " ",
							payloadJSONObject.optString(
								"externalReferenceCode"),
							" because \"", key, "\" changed"));
				}

				return false;
			}
		}

		return true;
	}

	private long _resolveCreatorUserId(JSONObject payloadJSONObject) {
		JSONObject creatorJSONObject = payloadJSONObject.optJSONObject(
			"creator");

		if (creatorJSONObject != null) {
			return creatorJSONObject.optLong("id", 0L);
		}

		return 0L;
	}

	// "ban" runs on C2M0Message (has a parent thread relationship) and on
	// C2M0Thread itself; "priority" runs only on C2M0Thread. Prefer whatever
	// the payload already carries; only reach for the thread lookup (an API
	// call) when the payload has no groupId of its own. An existing thread
	// carries its own id, so both cases resolve through it; a brand new
	// thread's first post carries neither and this returns 0.

	private long _resolveSiteIdByThread(
		JSONObject payloadJSONObject, String authToken) {

		long siteId = payloadJSONObject.optLong("groupId", 0L);

		if (siteId > 0L) {
			return siteId;
		}

		long threadId = payloadJSONObject.optLong(
			"r_threadMessages_c_c2m0ThreadId", 0L);

		if (threadId <= 0L) {
			threadId = payloadJSONObject.optLong("id", 0L);
		}

		return _forumModerationService.resolveSiteIdByThreadId(
			threadId, authToken);
	}

	private ResponseEntity<String> _respond(
		JSONObject payloadJSONObject, boolean validationCriteriaMet) {

		payloadJSONObject.put("validationCriteriaMet", validationCriteriaMet);

		return new ResponseEntity<>(
			payloadJSONObject.toString(), HttpStatus.OK);
	}

	private String _serviceAuthToken() {
		return _liferayOAuth2AccessTokenManager.getTokenValue(
			_OAUTH_APPLICATION_HEADLESS_SERVER_ERC);
	}

	private static final String _OAUTH_APPLICATION_HEADLESS_SERVER_ERC =
		"liferay-forums-etc-spring-boot-oahs";

	private static final Log _log = LogFactory.getLog(
		ObjectValidationRuleRestController.class);

	private static final Set<String> _ownFieldNames = Set.of(
		"lastPostDate", "notificationAuthorName", "notificationBody",
		"notificationMentionRecipientIds", "notificationReplyRecipientIds",
		"notificationTopicTitle");

	// Audit fields change on every write, and Aggregation fields are computed

	private static final Set<String> _uncomparedFieldNames = Set.of(
		"answerCount", "creator", "dateCreated", "dateModified",
		"externalReferenceCode", "id", "messageCount", "status",
		"validatedFlagCount", "voteTotal");

	@Autowired
	private ForumMessageService _forumMessageService;

	@Autowired
	private ForumModerationService _forumModerationService;

	@Autowired
	private LiferayOAuth2AccessTokenManager _liferayOAuth2AccessTokenManager;

}