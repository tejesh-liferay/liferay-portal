<style>
	.forums-message-card .card-body {
		padding: 1.25rem 1.5rem;
	}

	.forums-message-card__avatar-col {
		align-items: center;
		flex-shrink: 0;
		justify-content: center;
		margin-right: 1.25rem;
		text-align: center;
		width: 96px;
	}

	.forums-message-card__username {
		font-size: 0.8125rem;
		font-weight: 600;
		line-height: 1.2;
		margin-top: 0.5rem;
		max-width: 100%;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}

	.forums-message-card__title {
		font-size: 1.0625rem;
	}

	.forums-message-card__title a {
		color: var(--body-color, #272833);
	}

	.forums-message-card__title a:hover {
		color: var(--primary, #0b5fff);
	}

	.forums-message-card__preview {
		-webkit-box-orient: vertical;
		-webkit-line-clamp: 2;
		display: -webkit-box;
		margin-bottom: 0.5rem;
		overflow: hidden;
	}

	.forums-message-card__tags {
		display: flex;
		flex-wrap: wrap;
		gap: 0.25rem;
		margin-bottom: 0.5rem;
	}

	.forums-message-card__tag {
		background-color: var(--secondary-l3, #e7e7ed);
		border-color: var(--secondary-l3, #e7e7ed);
		color: var(--dark-d2, #111116);
	}

	.forums-message-card__meta {
		align-items: center;
		display: flex;
		flex-wrap: wrap;
		gap: 0.25rem 1rem;
	}

	.forums-message-card__meta-item {
		align-items: center;
		display: inline-flex;
		gap: 0.25rem;
	}

	.forums-message-card__meta-item svg {
		height: 14px;
		width: 14px;
	}

	@media (max-width: 767.98px) {
		.forums-message-card__avatar-col {
			align-items: center;
			display: flex;
			gap: 0.5rem;
			width: auto;
		}
	}
	.forums-search-results .forums-message-card {
		margin-bottom: 1rem;
	}

	.forums-search-results .search-document-tags {
		margin-bottom: 0.5rem;
	}
</style>

<div class="c-mb-4 c-mt-4 search-total-label">
	<#if searchContainer.getTotal() == 1>
		${languageUtil.format(locale, "x-result-for-x", [searchContainer.getTotal(), "<strong>" + htmlUtil.escape(searchResultsPortletDisplayContext.getKeywords()) + "</strong>"], false)}
	<#else>
		${languageUtil.format(locale, "x-results-for-x", [searchContainer.getTotal(), "<strong>" + htmlUtil.escape(searchResultsPortletDisplayContext.getKeywords()) + "</strong>"], false)}
	</#if>
</div>

<div class="forums-search-results">
	<#if entries?has_content>
		<#list entries as entry>
			<div class="card forums-message-card">
				<div class="card-body">
					<div class="autofit-row">
						<div class="autofit-col forums-message-card__avatar-col">
							<@liferay_ui["user-portrait"] userId=entry.getAssetEntryUserId() />

							<div class="forums-message-card__username text-secondary text-truncate">
								${htmlUtil.escape(entry.getCreatorUserName())}
							</div>
						</div>

						<div class="autofit-col autofit-col-expand forums-message-card__content">
							<h5 class="card-title forums-message-card__title">
								<a href="${entry.getViewURL()}">${entry.getHighlightedTitle()}</a>
							</h5>

							<#if entry.isAssetCategoriesOrTagsVisible()>
								<div class="search-document-tags">
									<@liferay_asset["asset-tags-summary"]
										className=entry.getClassName()
										classPK=entry.getClassPK()
										paramName=entry.getFieldAssetTagNames()
										portletURL=entry.getPortletURL()
									/>

									<@liferay_asset["asset-categories-summary"]
										className=entry.getClassName()
										classPK=entry.getClassPK()
										paramName=entry.getFieldAssetCategoryIds()
										portletURL=entry.getPortletURL()
									/>
								</div>
							</#if>

							<div class="forums-message-card__meta text-secondary small">
								<span class="forums-message-card__meta-item">
									<@clay.icon symbol="time" />

									${entry.getCreationDateString()}
								</span>
							</div>
						</div>
					</div>
				</div>
			</div>
		</#list>
	</#if>
</div>
