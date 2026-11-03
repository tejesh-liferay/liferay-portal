/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.forums;

import com.liferay.client.extension.util.spring.boot3.client.LiferayOAuth2AccessTokenManager;
import com.liferay.forums.service.ForumThreadService;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * @author Sai Tejesh
 */
@Component
public class ForumThreadLastPostDateBackfill {

	@EventListener(ApplicationReadyEvent.class)
	public void onApplicationReady() {
		_forumNotificationExecutor.execute(
			() -> {
				try {
					_forumThreadService.backfillLastPostDates(
						_liferayOAuth2AccessTokenManager.getTokenValue(
							_OAUTH_APPLICATION_HEADLESS_SERVER_ERC));
				}
				catch (Throwable throwable) {
					_log.error(
						"Unable to set the missing lastPostDate of threads",
						throwable);
				}
			});
	}

	private static final String _OAUTH_APPLICATION_HEADLESS_SERVER_ERC =
		"liferay-forums-etc-spring-boot-oahs";

	private static final Log _log = LogFactory.getLog(
		ForumThreadLastPostDateBackfill.class);

	@Autowired
	@Qualifier("forumNotificationExecutor")
	private ThreadPoolTaskExecutor _forumNotificationExecutor;

	@Autowired
	private ForumThreadService _forumThreadService;

	@Autowired
	private LiferayOAuth2AccessTokenManager _liferayOAuth2AccessTokenManager;

}
