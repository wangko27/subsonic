<form method="post" action="home.view" name="searchForm">
	<input type="hidden" name="listType" value="${model.listType}"/>
	<input type="hidden" name="listGroup" value="${model.listGroup}"/>
	<table><tr>
		<td><input type="text" name="query" id="query" size="50"></td>
		<td><a href="javascript:document.searchForm.submit()"><img width="16" height="16" src="<spring:theme code="searchImage"/>" alt="${search}" title="${search}"></a></td>
	</tr></table>
</form>
