/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.forums;

import com.liferay.client.extension.util.spring.boot3.BaseRestController;
import com.liferay.client.extension.util.spring.boot3.client.LiferayOAuth2AccessTokenManager;
import com.liferay.forums.service.ForumThreadService;

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
			"r_threadMessages_c_forumThreadId", 0L);

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

	private String _serviceAuthToken() {
		return _liferayOAuth2AccessTokenManager.getTokenValue(
			_OAUTH_APPLICATION_HEADLESS_SERVER_ERC);
	}

	private static final String _OAUTH_APPLICATION_HEADLESS_SERVER_ERC =
		"liferay-forums-etc-spring-boot-oahs";

	private static final Log _log = LogFactory.getLog(
		ForumThreadRestController.class);

	@Autowired
	@Qualifier("forumNotificationExecutor")
	private ThreadPoolTaskExecutor _forumNotificationExecutor;

	@Autowired
	private ForumThreadService _forumThreadService;

	@Autowired
	private LiferayOAuth2AccessTokenManager _liferayOAuth2AccessTokenManager;

}