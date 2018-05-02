<%@page pageEncoding="utf-8" 
contentType="text/html;charset=utf-8" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" 
	prefix="c" %>
<html>
	<head></head>
	<body>
		<table width="60%" cellpadding="2" cellspacing="0">
			<tr>
				<td>编号</td>
				<td>姓名</td>
				<td>年龄</td>
			</tr>
			<c:forEach items="${emps}" var="e">
			<tr>
				<td>${e.id }</td>
				<td>${e.name }</td>
				<td>${e.age }</td>
			</tr>
			</c:forEach>
		</table>
	</body>
</html>


