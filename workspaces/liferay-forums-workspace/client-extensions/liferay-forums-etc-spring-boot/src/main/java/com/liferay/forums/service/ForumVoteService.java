/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.forums.service;

import com.liferay.forums.client.LiferayApiClient;
import com.liferay.petra.string.StringBundler;

import java.net.URLEncoder;

import java.nio.charset.StandardCharsets;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * @author Roselaine Marques
 */
@Service
public class ForumVoteService {

	public void recalculateVoteScore(
		long messageId, long siteId, String authToken) {

		if ((messageId <= 0) || (siteId <= 0)) {
			if (_log.isWarnEnabled()) {
				_log.warn(
					StringBundler.concat(
						"Unable to recalculate the vote score for message ",
						messageId, " without a site scope"));
			}

			return;
		}

		try {

			// Relationship fields compare as strings, so quote the id.

			String filter = "r_messageVotes_c_forumMessageId eq '" +
				messageId + "'";

			JSONArray itemsJSONArray = new JSONObject(
				_liferayApiClient.get(
					StringBundler.concat(
						"/o/c/forumvotes/scopes/", siteId,
						"?fields=voteValue&pageSize=-1&filter=",
						_encode(filter)),
					authToken)
			).optJSONArray(
				"items"
			);

			int voteScore = 0;

			if (itemsJSONArray != null) {
				for (int i = 0; i < itemsJSONArray.length(); i++) {
					JSONObject itemJSONObject = itemsJSONArray.optJSONObject(
						i);

					if (itemJSONObject != null) {
						voteScore += itemJSONObject.optInt("voteValue", 0);
					}
				}
			}

			_liferayApiClient.patch(
				"/o/c/forummessages/" + messageId, authToken,
				new JSONObject(
				).put(
					"voteScore", voteScore
				).toString());
		}
		catch (Exception exception) {
			_log.error(
				StringBundler.concat(
					"Unable to recalculate the vote score for message ",
					messageId, ": ", exception.getMessage()));
		}
	}

	private String _encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static final Log _log = LogFactory.getLog(ForumVoteService.class);

	@Autowired
	private LiferayApiClient _liferayApiClient;

}
