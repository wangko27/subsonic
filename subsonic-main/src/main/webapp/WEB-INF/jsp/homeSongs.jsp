<h2>
<a href="javascript:noop()" onclick="top.playlist.onPlay(${model.trackIds}, 'P');">Play all</a> |
<a href="javascript:noop()" onclick="top.playlist.onPlay(${model.trackIds}, 'E');">Enqueue all</a> |
<a href="javascript:noop()" onclick="top.playlist.onPlay(${model.trackIds}, 'A');">Add all</a>
</h2>

<%@ include file="songs.jsp" %>

<%@ include file="homePrevNext.jsp" %>
