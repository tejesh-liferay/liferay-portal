/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.forums.service;

import com.liferay.forums.client.LiferayApiClient;
import com.liferay.petra.string.StringBundler;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.json.JSONObject;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * @author Tejesh Boggavarapu
 */
@Service
public class ForumMessageService {

	// A message's treePath lists its ancestors' IDs, top-level reply first,
	// each followed by "/": "/" for a top-level reply, "/47819/48685/" for a
	// reply two levels down. It excludes the message's own ID, so it is known
	// before the message is created and is written with it.

	public boolean isTreePathValid(
		String treePath, long parentMessageId, String authToken) {

		if (parentMessageId <= 0) {
			return treePath.equals("/");
		}

		String parentTreePath = _getTreePath(parentMessageId, authToken);

		if ((parentTreePath == null) || parentTreePath.isEmpty()) {

			// The parent predates treePath and has not been backfilled, so
			// only the last segment can be checked

			if (treePath.matches("^/(\\d+/)*$") &&
				treePath.endsWith("/" + parentMessageId + "/")) {

				return true;
			}

			return false;
		}

		return treePath.equals(parentTreePath + parentMessageId + "/");
	}

	private String _getTreePath(long messageId, String authToken) {
		try {
			return new JSONObject(
				_liferayApiClient.get(
					StringBundler.concat(
						"/o/c/c2m0messages/", messageId, "?fields=treePath"),
					authToken)
			).optString(
				"treePath", ""
			);
		}
		catch (Exception exception) {
			_log.error(
				StringBundler.concat(
					"Unable to read the tree path of message ", messageId, ": ",
					exception.getMessage()));

			return null;
		}
	}

	private static final Log _log = LogFactory.getLog(
		ForumMessageService.class);

	@Autowired
	private LiferayApiClient _liferayApiClient;

}