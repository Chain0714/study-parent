<%@page pageEncoding="utf-8" 
contentType="text/html;charset=utf-8" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" 
	prefix="c" %>
<html>
	<head></head>
	<body>
		<table width="60%" cellpadding="2" cellspacing="0">
			<tr>
				<td><h1>函数</h1></td>
			</tr>
            <tr>
			    <c:forEach items="${funs}" var="f">

			        <td><a href="getFun.do?name=${f.name}">${f.name }</a></td>

			    </c:forEach>
            </tr>
		</table>
		<h1>新建函数</h1>
		<form method="post" action="addFun.do" enctype="multipart/form-data">
        	<span>函数名称：</span><input name ="functionName" id="functionName" /></br>
        	<span>入参名称：</span><input name ="inputKey" id="inputKey" /></br>
            <span>出参名称：</span><input name ="outputKey" id="outputKey" /></br>
        	<span>函数内容：</span><textarea name="functionContent" rows="8" style="width: 650px"></textarea></br>
        	<span>函数文件：</span><input type="file" name="file"/>
        	</br>
        	<input type="submit" value="新建"/>
        </form>

	</body>
</html>


