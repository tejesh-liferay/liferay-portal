/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.forums.service;

import com.liferay.forums.client.LiferayApiClient;
import com.liferay.petra.string.StringBundler;

import java.time.LocalDate;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.json.JSONObject;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * @author Roselaine Marques
 */
@Service
public class ForumThreadService {

	public void updateLastPostDate(long threadId, String authToken) {
		if (threadId <= 0) {
			if (_log.isWarnEnabled()) {
				_log.warn(
					StringBundler.concat(
						"Unable to update lastPostDate for thread ", threadId,
						" without a valid id"));
			}

			return;
		}

		try {
			_liferayApiClient.patch(
				"/o/c/forumthreads/" + threadId, authToken,
				new JSONObject(
				).put(
					"lastPostDate",
					LocalDate.now(
					).toString()
				).toString());
		}
		catch (Exception exception) {
			_log.error(
				StringBundler.concat(
					"Unable to update lastPostDate for thread ", threadId, ": ",
					exception.getMessage()));
		}
	}

	private static final Log _log = LogFactory.getLog(ForumThreadService.class);

	@Autowired
	private LiferayApiClient _liferayApiClient;

}