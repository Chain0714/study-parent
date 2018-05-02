<%@page pageEncoding="utf-8" 
contentType="text/html;charset=utf-8" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" 
	prefix="c" %>
<html>
	<head>
	    <c:set var="ctx" value="${pageContext.request.contextPath}"/>
	    <script type="text/javascript" src="${ctx }/script/jquery-1.7.2.min.js"></script>
	</head>
	<body>

		<table width="60%" cellpadding="2" cellspacing="0">
            <tr>
			  <c:forEach items="${funs}" var="f">
			    <td>
				    ${f.name }
                </td>
			  </c:forEach>
			</tr>

		</table>
		</br></br>
        	<span>函数名称：</span><input name ="functionName" id="functionName" value="${fun.name}" disabled="true"/></br></br>
        	<span>入参：</span></br></br>
        	<c:forEach items="${fun.inputKeys}" var="key">
                <span>${key}:</span><input class="inKey" name ="${key}" id="${key}" style = 'width:1000px'/></br>
            </c:forEach>
            </br></br>
            <span>出参：</span></br></br>
            <c:forEach items="${fun.outputKeys}" var="key">
                <span>${key}:</span><input name ="${key}" class="outKey" id="${key}" style = 'width:1000px'/></br>
            </c:forEach>
            </br></br>
        	<input type="button" value="调用" onClick="invoke();"/>
        	<input type="button" value="删除" onClick="del();"/>
        	<input type="button" value="返回" onclick="javascript:history.back(-1);"></br></br></br></br>
        	<h2>链式调用</h2>
        	<input id="functions" type="text" name="functions" value="${fun.name},"style = 'width:500px'/><input type="button" value="开始" onClick="chainInvoke();"/>
        	</br>
        	<span id="chainResult"></span>

	</body>
    <script type="text/javascript">
        function chainInvoke(){
            var params={};
            $(".inKey").each(function(){
                params[$(this).attr("name")]=$(this).val();
            });
            var value = JSON.stringify(params);
            var functions=$("#functions").val();
            $.get(
                            "chianInvoke.do",
                            {"functions":functions,"params":value},
                            function(result){
                                if(""==result){
                                     $("#chainResult").css("color","red");
                                     $("#chainResult").html("函数链组合不合法");
                                }else{
                                     var jsonObj=JSON.parse(result);
                                     $("#chainResult").removeAttr("style");
                                     for(var p in jsonObj){
                                         $("#chainResult").empty();
                                         var span=$("<span></span>").text(p+"=");
                                         var span1=$("<span></span>").text(jsonObj[p]);
                                         var br="</br>"
                                          $("#chainResult").append(span,span1,br);
                                     }
                                }

                            }
                        );
        }

        function del(){
            var name = $("#functionName").val();
            location.href="delFun.do?name="+name;
        }

        function invoke(){
            var params={};
            $(".inKey").each(function(){
                params[$(this).attr("name")]=$(this).val();
            });
            var value = JSON.stringify(params);
            var name = $("#functionName").val();
            $.post(
                "invoke.do",
                {"name":name,"params":value},
                function(result){
                    var jsonObj=JSON.parse(result);
                    $(".outKey").each(function(){
                        $(this).val(jsonObj[$(this).attr("name")]);
                    });
                }
            );
        }

    </script>
</html>


