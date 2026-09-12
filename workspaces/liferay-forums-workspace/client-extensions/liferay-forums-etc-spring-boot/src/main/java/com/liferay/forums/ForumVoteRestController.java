/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.forums;

import com.liferay.client.extension.util.spring.boot3.BaseRestController;
import com.liferay.client.extension.util.spring.boot3.client.LiferayOAuth2AccessTokenManager;
import com.liferay.forums.service.ForumModerationService;
import com.liferay.forums.service.ForumVoteService;

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
 * Persists {@code ForumMessage.voteScore} with the service account's
 * elevated permission. The browser only ever creates/deletes {@code
 * ForumVote} entries, which it already has {@code ADD_OBJECT_ENTRY} for;
 * this trigger recalculates the denormalized score.
 *
 * @author Roselaine Marques
 */
@RestController
public class ForumVoteRestController extends BaseRestController {

	@PostMapping("/object-action/recalculate-vote-score")
	public ResponseEntity<String> onRecalculateVoteScore(
			@AuthenticationPrincipal Jwt jwt, @RequestBody String json)
		throws Exception {

		if (jwt != null) {
			log(jwt, _log, json);
		}
		else if (_log.isInfoEnabled()) {
			_log.info(json);
		}

		String authToken = _serviceAuthToken();

		_forumNotificationExecutor.execute(
			() -> _fanOut(
				"recalculate-vote-score",
				() -> _processRecalculateVoteScore(json, authToken)));

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

	private void _processRecalculateVoteScore(String json, String authToken) {
		JSONObject payloadJSONObject = new JSONObject(json);

		JSONObject objectEntryJSONObject = payloadJSONObject.optJSONObject(
			"objectEntry");

		JSONObject valuesJSONObject = (objectEntryJSONObject != null) ?
			objectEntryJSONObject.optJSONObject("values") : null;

		if (valuesJSONObject == null) {
			if (_log.isWarnEnabled()) {
				_log.warn(
					"onRecalculateVoteScore: the payload carries no entry " +
						"values");
			}

			return;
		}

		long messageId = valuesJSONObject.optLong(
			"r_messageVotes_c_forumMessageId", 0L);

		if (messageId == 0L) {
			if (_log.isWarnEnabled()) {
				_log.warn(
					"onRecalculateVoteScore: missing " +
						"r_messageVotes_c_forumMessageId in payload");
			}

			return;
		}

		long siteId = (objectEntryJSONObject != null) ?
			objectEntryJSONObject.optLong("groupId", 0L) : 0L;

		if (siteId <= 0L) {
			siteId = _forumModerationService.resolveSiteId(authToken);
		}

		_forumVoteService.recalculateVoteScore(messageId, siteId, authToken);
	}

	private String _serviceAuthToken() {
		return _liferayOAuth2AccessTokenManager.getTokenValue(
			_OAUTH_APPLICATION_HEADLESS_SERVER_ERC);
	}

	private static final String _OAUTH_APPLICATION_HEADLESS_SERVER_ERC =
		"liferay-forums-etc-spring-boot-oahs";

	private static final Log _log = LogFactory.getLog(
		ForumVoteRestController.class);

	@Autowired
	private ForumModerationService _forumModerationService;

	@Autowired
	@Qualifier("forumNotificationExecutor")
	private ThreadPoolTaskExecutor _forumNotificationExecutor;

	@Autowired
	private ForumVoteService _forumVoteService;

	@Autowired
	private LiferayOAuth2AccessTokenManager _liferayOAuth2AccessTokenManager;

}
