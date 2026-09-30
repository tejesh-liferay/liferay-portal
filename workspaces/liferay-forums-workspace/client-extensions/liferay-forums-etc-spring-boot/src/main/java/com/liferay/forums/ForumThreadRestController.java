/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.forums;

import com.liferay.client.extension.util.spring.boot3.BaseRestController;
import com.liferay.client.extension.util.spring.boot3.client.LiferayOAuth2AccessTokenManager;
import com.liferay.forums.service.ForumModerationService;
import com.liferay.forums.service.ForumThreadService;
import com.liferay.petra.string.StringBundler;
import com.liferay.portal.kernel.util.GetterUtil;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.json.JSONObject;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Keeps {@code ForumThread.lastPostDate} current. Fired {@code onAfterAdd}
 * on {@code ForumMessage} — the original post and every reply are both
 * {@code ForumMessage} rows, so this single trigger both defaults a new
 * thread's {@code lastPostDate} (the original post fires it first) and
 * bumps it on every subsequent reply.
 *
 * @author Roselaine Marques
 */
@RestController
public class ForumThreadRestController extends BaseRestController {

	// Fired onAfterAdd and onAfterUpdate on C2M0Thread. A validation rule
	// cannot enforce this: its payload names the thread's creator, not the
	// user making the change, and carries no site. An action's token is the
	// acting user's, so a priority change by anyone who cannot moderate the
	// forum is reverted to the value it had before.

	@PostMapping("/object-action/enforce-thread-priority")
	public ResponseEntity<String> onEnforceThreadPriority(
			@AuthenticationPrincipal Jwt jwt, @RequestBody String json)
		throws Exception {

		if (jwt != null) {
			log(jwt, _log, json);
		}
		else if (_log.isInfoEnabled()) {
			_log.info(json);
		}

		// The revert below runs as the service account and fires
		// onAfterUpdate again; it must not be checked, or it is reverted too

		if (_isServiceAccountActor(jwt)) {
			return new ResponseEntity<>(json, HttpStatus.OK);
		}

		String authToken = _serviceAuthToken();

		long actorUserId = _resolveActorUserId(jwt);

		_forumNotificationExecutor.execute(
			() -> _fanOut(
				"enforce-thread-priority",
				() -> _processEnforceThreadPriority(
					json, actorUserId, authToken)));

		return new ResponseEntity<>(json, HttpStatus.OK);
	}

	@PostMapping("/object-action/update-thread-last-post-date")
	public ResponseEntity<String> onUpdateThreadLastPostDate(
			@AuthenticationPrincipal Jwt jwt, @RequestBody String json)
		throws Exception {

		String authToken = _serviceAuthToken();

		if (jwt != null) {
			log(jwt, _log, json);
		}
		else if (_log.isInfoEnabled()) {
			_log.info(json);
		}

		_forumNotificationExecutor.execute(
			() -> _fanOut(
				"update-thread-last-post-date",
				() -> _processUpdateThreadLastPostDate(json, authToken)));

		return new ResponseEntity<>(json, HttpStatus.OK);
	}

	private void _fanOut(String handler, Runnable task) {
		try {
			task.run();
		}
		catch (Throwable throwable) {
			_log.error(
				"Unhandled failure in " + handler + " fan-out", throwable);
		}
	}

	private double _getPriority(JSONObject objectEntryJSONObject) {
		if (objectEntryJSONObject == null) {
			return 0;
		}

		JSONObject valuesJSONObject = objectEntryJSONObject.optJSONObject(
			"values");

		if (valuesJSONObject == null) {
			return 0;
		}

		return valuesJSONObject.optDouble("priority", 0);
	}

	private boolean _isServiceAccountActor(Jwt jwt) {
		if (jwt == null) {
			return false;
		}

		return _SERVICE_ACCOUNT_USER_NAME.equals(
			jwt.getClaimAsString("username"));
	}

	private void _processEnforceThreadPriority(
		String json, long actorUserId, String authToken) {

		JSONObject payloadJSONObject = new JSONObject(json);

		JSONObject objectEntryJSONObject = payloadJSONObject.optJSONObject(
			"objectEntry");

		if (objectEntryJSONObject == null) {
			if (_log.isWarnEnabled()) {
				_log.warn(
					"onEnforceThreadPriority: the payload carries no entry");
			}

			return;
		}

		// A new thread has no original entry, so its original priority is 0

		double originalPriority = _getPriority(
			payloadJSONObject.optJSONObject("originalObjectEntry"));
		double priority = _getPriority(objectEntryJSONObject);

		if ((Double.compare(priority, originalPriority) == 0) ||
			_forumModerationService.canModerateForum(actorUserId, authToken)) {

			return;
		}

		long threadId = payloadJSONObject.optLong("classPK", 0L);

		if (_log.isInfoEnabled()) {
			_log.info(
				StringBundler.concat(
					"Refused a priority change on thread ", threadId, " from ",
					originalPriority, " to ", priority, " by user ",
					actorUserId));
		}

		_forumThreadService.revertPriority(
			threadId, originalPriority, authToken);
	}

	private void _processUpdateThreadLastPostDate(
		String json, String authToken) {

		JSONObject payloadJSONObject = new JSONObject(json);

		JSONObject objectEntryJSONObject = payloadJSONObject.optJSONObject(
			"objectEntry");

		JSONObject valuesJSONObject = (objectEntryJSONObject != null) ?
			objectEntryJSONObject.optJSONObject("values") : null;

		if (valuesJSONObject == null) {
			if (_log.isWarnEnabled()) {
				_log.warn(
					"onUpdateThreadLastPostDate: the payload carries no " +
						"entry values");
			}

			return;
		}

		long threadId = valuesJSONObject.optLong(
			"r_threadMessages_c_c2m0ThreadId", 0L);

		if (threadId == 0L) {
			if (_log.isWarnEnabled()) {
				_log.warn(
					"onUpdateThreadLastPostDate: unable to resolve a " +
						"threadId from the payload");
			}

			return;
		}

		_forumThreadService.updateLastPostDate(threadId, authToken);
	}

	private long _resolveActorUserId(Jwt jwt) {
		if (jwt == null) {
			return 0L;
		}

		return GetterUtil.getLong(jwt.getClaimAsString("sub"));
	}

	private String _serviceAuthToken() {
		return _liferayOAuth2AccessTokenManager.getTokenValue(
			_OAUTH_APPLICATION_HEADLESS_SERVER_ERC);
	}

	private static final String _OAUTH_APPLICATION_HEADLESS_SERVER_ERC =
		"liferay-forums-etc-spring-boot-oahs";

	private static final String _SERVICE_ACCOUNT_USER_NAME =
		"default-service-account";

	private static final Log _log = LogFactory.getLog(
		ForumThreadRestController.class);

	@Autowired
	private ForumModerationService _forumModerationService;

	@Autowired
	@Qualifier("forumNotificationExecutor")
	private ThreadPoolTaskExecutor _forumNotificationExecutor;

	@Autowired
	private ForumThreadService _forumThreadService;

	@Autowired
	private LiferayOAuth2AccessTokenManager _liferayOAuth2AccessTokenManager;

}