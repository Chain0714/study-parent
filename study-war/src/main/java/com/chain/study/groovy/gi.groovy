package com.chain.study.groovy

import com.chain.study.container.BaseGroovyInterface
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.util.regex.Matcher

/**
 * Created by 17032651 on 2017/6/22.
 */
class gi implements BaseGroovyInterface{
    Map<String, Object> run(Map<String, Object> params) {
        String json = params.get("json");
        def paramsMap = new JsonSlurper().parseText(json) as Map
        def fraudCheckList             = paramsMap['fraudCheckResult']?:[]  as List
        def manualFlag                 = paramsMap['manualFlag']?:false     as boolean  //不通过是否转人工

        List<Rule> ruleChain           = BASE_MODEL_RULE_CHAIN +
                R360_RULE_CHAIN +
                CREDIT_REFERENCE_RULE_CHAIN + //人行报告
                EXISTING_USER_MODEL_RULE +    //存量用户
                PY_CREDIT_REFERENCE_RULE +
                BLANK_USER_MODEL_AGE_RULE+
                CSIRFDS_RULE_CHAIN

        // 运行规则链条
        List<Map> checklist = ruleChain.collect {
            rule ->
                Rule.Result result = rule(paramsMap)
                String resultDesc
                String resultDetail

                if(result == Rule.Result.R0000){
                    resultDesc    = '通过'
                    resultDetail  = '校验通过：准入'
                }else if(result == Rule.Result.R00001){
                    resultDesc    = '不通过'
                    resultDetail  = '不通过：人工审核'
                }else{
                    resultDesc    = '不通过'
                    resultDetail  = result.desc + '：拒绝'
                }
                //special cases
                switch (rule.code()){
                //不直接参与决策的项
                    case CREDIT_REFERENCE_EXIST_RULE.code(): //是否存在人行报告校验项
                    case CREDIT_REFERENCE_PERSONAL_INFO_RULE.code()://人行三要素是否一致
                        resultDetail = ''
                        break
                    case CREDIT_REFERENCE_SCORE_MODEL_RULE.code(): //人行评分
                        resultDesc = (paramsMap['_creditScore'] as double)?.round(2)
                        if(result == Rule.Result.R0000)
                            resultDetail = '人行评分模型得分>561分：准入'
                        else if(result == Rule.Result.R022017 || result == Rule.Result.R022018)
                            resultDetail = result.desc + '：准入'
                        break
                    case EXISTING_USER_MODEL_RULE.code(): //存量评分
                        resultDesc = (paramsMap['_existingUserModelScore'] as double)?.round(2)
                        if(result == Rule.Result.R0000)
                            resultDetail = '存量用户评分模型得分>700分：准入'
                        else if(result == Rule.Result.R00001)
                            resultDetail = '存量用户评分模型得分660至700分：人工审核'
                        else if(result == Rule.Result.R023003)
                            resultDetail = result.desc + '：准入'
                        break
                    case PY_CREDIT_REFERENCE_RULE.code():
                        if(result != Rule.Result.R0000){
                            if(!paramsMap['_isCreditReferenceModel'] as boolean)               // 是否有人行报告
                                resultDetail = Rule.Result.R025007.desc + '：拒绝'
                            else if(!paramsMap['_isPersonalInfoMatch'] as boolean)             // 人行报告中三要素是否匹配
                                resultDetail = Rule.Result.R025008.desc + '：拒绝'
                            else
                                resultDetail = '校验通过：准入'
                        }
                        break
                //特殊校验， 放宽融360银联智测3项校验 - 直接通过
                    case R360_UNION_PAY_CREDIT_CST_RULE.code():
                    case R360_UNION_PAY_CREDIT_COT_RULE.code():
                    case R360_UNION_PAY_CREDIT_RSK_RULE.code():
                    case R360_INNER_CREDIT_USER_SOCIAL_SECURITY_RULE.code():
                    case R360_INNER_CREDIT_QID77_RULE.code():
                    case R360_LONG_POSITION_CREDIT_RULE.code():
                    case R360_INNER_CREDIT_EMERGENCY1_TEL_FEQUENCY_RULE.code():
                    case R360_INNER_CREDIT_CREDIT_OVERDUE_RULE.code():
                        resultDetail = result == Rule.Result.R0000 ? '校验通过：准入' : '不通过：准入'
                        break
                }
                [
                        'checkCode'   : rule.code(),
                        'resultDesc'  : resultDesc,
                        'resultDetail': resultDetail
                ]
        }

        // 匹配最终返回结果
        // 1.只要含有一项拒绝，返回拒绝
        // 2.含有一项或多项转人工，则转人工
        // 3.其他情况通过
//    ReturnResult returnResult = fraudCheckList.inject(ReturnResult.APPROVED){
//      preResult, checkResult ->
//        if(preResult == ReturnResult.REJECTED || checkResult?.get('resultDesc') == '不通过')
//          return ReturnResult.REJECTED
//        preResult
//    }

        ReturnResult returnResult = (fraudCheckList + checklist).inject(ReturnResult.APPROVED){
            preResult, checkResult ->
                if(checkResult['checkCode'] == CREDIT_REFERENCE_EXIST_RULE.code())
                    return preResult

                if(preResult == ReturnResult.REJECTED)
                    return ReturnResult.REJECTED

                ReturnResult thenResult = preResult
                switch (checkResult?.get('resultDetail')){
                    case { it ==~ /.*?准入$/}:
                        break
                    case { it ==~ /.*?人工审核$/}:
                        thenResult = ReturnResult.MANUALLY
                        break
                    case { it ==~ /.*?拒绝$/}:
                        thenResult = ReturnResult.REJECTED
                        break
                }
                thenResult
        }

        def cashQuota     = calcCashQuota(paramsMap)
        def quota         = calcQuota(paramsMap)
        def consumeQuota  = quota - cashQuota
        def cqImmutable   = consumeQuota

        //special logical block - start
        def specialCheck  = [
                'checkCode'   : 'QC001',
                'resultDesc'  : cqImmutable,
                'resultDetail': "计算消费额度为${cqImmutable}元".toString()
        ]
        if(cqImmutable < 1000){
            if(paramsMap['_isCreditReferenceModel'] && returnResult == ReturnResult.APPROVED){ //有人行报告且额度小于1000元的，不触碰拒绝或转人工规则可直接通过的，额度提到1000元直接通过。
                consumeQuota = 1000
                specialCheck['resultDetail']  = "有人行报告且额度小于1000元（${cqImmutable}元）且不触碰拒绝或转人工规则，额度提到1000元".toString()
            }else{ //没有人行报告且额度小于1000元的，拒绝处理
                returnResult = ReturnResult.REJECTED
                consumeQuota = 0
                specialCheck['resultDetail'] = "没有人行报告且额度小于1000元（${cqImmutable}元）：拒绝".toString()
            }
            if(returnResult == ReturnResult.MANUALLY){
                consumeQuota = 1000
                specialCheck['resultDetail'] = "转人工的额度小于1000元（${cqImmutable}元）提至1000元".toString()
            }
            specialCheck['resultDesc']    = consumeQuota
        }

        if(returnResult == ReturnResult.REJECTED){
            consumeQuota = 0
        }
        specialCheck['resultDesc']    = consumeQuota.toString()
        //special logical block - end

        checklist += specialCheck

        if(manualFlag && returnResult == ReturnResult.REJECTED)
            returnResult = ReturnResult.MANUALLY

        //人行征信评分 - 纪录评分到结果中
        def result =
        [
                'isAdmit': returnResult.admit,
                'resultCode': returnResult.value,
                'resultDesc': returnResult.desc,
                'cashQuota': cashQuota,
                'consumptionQuota': consumeQuota,
                'checkList': checklist
        ]

        return ["json":new JsonOutput().toJson(result)]
    }
    static boolean isCreditReferenceModel(List<Map> creditRefReports){
        creditRefReports && creditRefReports.size()>0 &&
                creditRefReports.first()?.get('rhzx_flag')?.toString() == "1"
    }

    static boolean isExistingUserModel(Map creditindex){
        creditindex && calcDayPeriod(new Date(), parseDate(creditindex?.get('cfc_rgst_dt') as String, FULL_DATE_FORMAT_1)) > 365
    }

    static Date parseDate(String dateStr, String dateFormat){
        Date date = null
        try{
            date = Date.parse(dateFormat, dateStr)
        }catch (ignore){}
        date
    }

    static long parseLong(String numStr){
        long val = -1L
        try{
            val = Math.round(Double.parseDouble(numStr))
        }catch (ignore){}
        val
    }

    static boolean isInAnYearPeriod(Date date1, Date date2){
        try{
            Calendar cal1 = Calendar.getInstance()
            cal1.setTime(date1)
            Calendar cal2 = Calendar.getInstance()
            cal2.setTime(date2)

            int yearPeriod = cal1.get(Calendar.YEAR) - cal2.get(Calendar.YEAR)

            if(yearPeriod == 1){
                int mth1 = cal1.get(Calendar.MONTH)
                int mth2 = cal2.get(Calendar.MONTH)
                if(mth1 == mth2){
                    int day1 = cal1.get(Calendar.DAY_OF_MONTH)
                    int day2 = cal2.get(Calendar.DAY_OF_MONTH)
                    return day1 <= day2
                }
                return mth1 < mth2
            }
            return yearPeriod == 0
        }catch (ignore){
        }
        false
    }

    static int calcMonthPeriod(Date date1, Date date2){
        int monthPeriod = Integer.MIN_VALUE
        try{
            Calendar cal1 = Calendar.getInstance()
            cal1.setTime(date1)
            Calendar cal2 = Calendar.getInstance()
            cal2.setTime(date2)

            monthPeriod = (cal1.get(Calendar.YEAR) - cal2.get(Calendar.YEAR)) * 12 +
                    cal1.get(Calendar.MONTH) - cal2.get(Calendar.MONTH)
        }catch (ignore){
        }
        monthPeriod
    }

    static int calcDayPeriod(Date date1, Date date2){
        int dayPeriod = Integer.MIN_VALUE
        try{
            dayPeriod = date1 - date2
        }catch (ignore){
        }
        dayPeriod
    }

    static int calcAge(String cardId){
        int periodOfYear = Integer.MIN_VALUE
        try{
            Date birthDay = Date.parse('yyyyMMdd', cardId[6..13])

            def cal = Calendar.getInstance()
            def curYear = cal.get(Calendar.YEAR)
            def curMonth = cal.get(Calendar.MONTH)
            def curDay = cal.get(Calendar.DAY_OF_MONTH)

            def calOfBirthDay = Calendar.getInstance()
            calOfBirthDay.setTime(birthDay)
            def birthYear = calOfBirthDay.get(Calendar.YEAR)
            def birthMonth = calOfBirthDay.get(Calendar.MONTH)
            def birthDayOfMonth = calOfBirthDay.get(Calendar.DAY_OF_MONTH)

            periodOfYear  = curYear - birthYear

            periodOfYear += periodOfYear > 0 &&
                    (birthMonth > curMonth ||
                            birthMonth == curMonth && birthDayOfMonth > curDay ) ? -1 : 0

        }catch (ignored){
        }
        periodOfYear
    }

    /********************************************************************************
     * 1. 黑名单校验 (checked by PCIDS)- START                                        *
     *   1.1. 易购风控Minos易购黄牛黑名单                                               *
     *   1.2. CSI处罚中心-黑名单                                                       *
     *   1.3. CSI处罚中心-账户冻结状态                                                  *
     *   1.4. 大数据-任性付黑名单                                                       *
     *   1.5. 大数据-融360黑名单                                                       *
     ********************************************************************************/
    /**
     * checked by PCIDS
     */
    /********************************************************************************
     * 1. 黑名单校验 - END                                                            *
     ********************************************************************************/

    /********************************************************************************
     * 6. 鹏元征信（运营商信息校验）- START                                              *
     ********************************************************************************/
    static final Rule PY_CREDIT_REFERENCE_RULE = new Rule(){
        String code() { 'PY999'}

        String name() { '鹏元征信验证'}

        String desc() { '鹏元征信三要素（姓名、手机号码、身份证）是否一致' }

        Rule.Result call(Map paramsMap) {
            def bigDatePhone = paramsMap['dsjPhone']   as Map
            def pyResult = bigDatePhone?.get('result') as String

            if (pyResult == '0001')         // 无数据
                return Rule.Result.R025004
            if(pyResult == '3703')     // 接口超时
                return Rule.Result.R025005
            if(pyResult != '0000')
                return Rule.Result.R025006  // 鹏元数据异常

            // 大数据鹏元接口调用信息
            def pyCreditRef  = paramsMap['pyInfo'] as Map
            if('001' != pyCreditRef?.get('phoneCheckResult'))       // 手机号码不匹配
                return Rule.Result.R025001
            if('001' != pyCreditRef?.get('documentNoCheckResult'))  // 身份证号码不匹配
                return Rule.Result.R025002
            if('001' != pyCreditRef?.get('nameCheckResult'))        // 姓名不匹配
                return Rule.Result.R025003

            Rule.Result.R0000
        }
    }
    /********************************************************************************
     * 6. 鹏元征信（运营商信息校验）- END                                                *
     ********************************************************************************/

    /********************************************************************************
     * 7. 融360黑名单 - START                                                        *
     ********************************************************************************/
    static abstract class AbstractR360BaseRule implements Rule{
        Rule.Result call(Map paramsMap) {
            def r360Info  = paramsMap['r360Info'] as Map            // 融360数据
            null != r360Info?invoke(r360Info):success()
        }
        abstract Rule.Result invoke(Map r360Map)
        abstract String keyname()
        abstract Rule.Result error()
        Rule.Result success(){ Rule.Result.R0000 }
    }

    /**
     *  7.1 融360 _机构G黑名单是否命中 getOrgGBlacklistFeedBack
     */
    @Deprecated
    static abstract class AbstractR360BlackListFeedbackRule extends AbstractR360BaseRule{
        Rule.Result invoke(Map r360Map){
            def valMap  = r360Map.get(keyname())              as Map
            def code    = valMap?.get(R360_RESPONSE_CODE_KEY) as String
//      Map content = valMap?.get(R360_RESPONSE_CONTENT_KEY)
            code == R360_RESPONSE_OK && valMap?.get(IS_LIST_KEY) == IS_LIST_HINT ? error() : success()
        }
    }

    @Deprecated
    static final Rule R360_ORG_G_BLACKLIST_FEEDBACK_RULE = new AbstractR360BlackListFeedbackRule(){
        String code() { 'BL005' }

        String name() { '机构G黑名单验证' }

        String desc() { '借款人是否在融360机构G黑名单' }

        Rule.Result error() { Rule.Result.R026002 }

        String keyname() { 'getOrgGBlacklistFeedBack' }
    }

    /**
     *  7.2 融360 _百度征信黑名单是否命中 getBaiduBlacklistFeedBack
     */
    @Deprecated
    static final Rule R360_BAIDU_BLACKLIST_FEEDBACK_RULE = new AbstractR360BlackListFeedbackRule(){
        String code() { 'BL006' }

        String name() { '百度征信黑名单验证' }

        String desc() { '借款人是否在融360查询百度征信黑名单' }

        Rule.Result error() { Rule.Result.R026003 }

        String keyname() { 'getBaiduBlacklistFeedBack' }
    }

    /**
     *  7.3 融360 亿美黑名单是否命中 getYimeiBlacklistFeedBack
     */
    @Deprecated
    static final Rule R360_YIMEI_BLACKLIST_FEEDBACK_RULE = new AbstractR360BlackListFeedbackRule(){
        String code() { 'BL007' }

        String name() { '亿美黑名单验证' }

        String desc() { '借款人是否在融360查询亿美黑名单' }

        Rule.Result error() { Rule.Result.R026004 }

        String keyname() { 'getYimeiBlacklistFeedBack' }
    }


    /**
     *  7.4 融360 _百融黑名单是否命中 getBairongSpecialBlacklistFeedBack
     */
    @Deprecated
    static final Rule R360_BAIRONG_SPECIAL_BLACKLIST_FEEDBACK_RULE = new AbstractR360BlackListFeedbackRule(){
        String code() { 'BL008' }

        String name() { '百融特殊名单验证' }

        String desc() { '借款人是否在融360百融特殊名单' }

        Rule.Result error() { Rule.Result.R026005 }

        String keyname() { 'getBairongSpecialBlacklistFeedBack' }
    }

    /**
     *  7.5 融360 _机构B黑名单是否命中 getOrgBBlacklistFeedBack
     */
    @Deprecated
    static final Rule R360_ORG_B_BLACKLIST_FEEDBACK_RULE = new AbstractR360BlackListFeedbackRule(){
        String code() { 'BL004' }

        String name() { '机构B黑名单验证' }

        String desc() { '借款人是否在融360机构B黑名单' }

        Rule.Result error() { Rule.Result.R026001 }

        String keyname() { 'getOrgBBlacklistFeedBack' }
    }

    /********************************************************************************
     * 7. 融360黑名单 - END                                                          *
     ********************************************************************************/

    /********************************************************************************
     * 8. 融360运营商信息 - START                                                     *
     ********************************************************************************/
    static abstract class AbstractR360PhoneRule extends AbstractR360BaseRule{

        Rule.Result invoke(Map r360Map) {
            def       valMap = r360Map.get(keyname())                 as Map
            def       status = valMap?.get(R360_RESPONSE_CODE_KEY)    as String
            if(status != R360_RESPONSE_OK)
                return success()
            def      content = valMap?.get(R360_RESPONSE_CONTENT_KEY) as Map
            def          RSL = content?.get('RSL')                    as List<Map>
            def     firstRSL = RSL?.first()                           as Map
            def           rs = firstRSL?.get('RS')                    as Map

            null != rs?invokePhoneRule(rs['code'] as String, rs['desc'] as String):success()
        }

        abstract Rule.Result invokePhoneRule(String code, String desc)
    }

    static abstract class AbstractR360PhoneRule2 extends AbstractR360PhoneRule{
        @Override
        Rule.Result invoke(Map r360Map) {
            def        valMap   = r360Map.get(keyname())                  as Map
            def        status   = valMap?.get(R360_RESPONSE_CODE_KEY)     as String
            if(status != R360_RESPONSE_OK)
                return success()
            def       content   = valMap?.get(R360_RESPONSE_CONTENT_KEY)  as Map
            def  checkStatus    = content?.get('checkStatus')             as String
            if(checkStatus != 'S')
                return success()
            def    checkResult  = content?.get('checkResult')             as Map
            def            RSL  = checkResult?.get('RSL')                 as List<Map>
            def      firstRSL   = RSL?.first()                            as Map
            def            rs   = firstRSL?.get('RS')                     as Map

            null != rs?invokePhoneRule(rs['code'] as String, rs['desc'] as String):success()
        }
    }

    /**
     *  8.1 在线时长 getOnLineTime
     *  {
     *    "content": {
     *      "ISPNUM": {             //手机号码归属地
     *        "province": "陕西",
     *        "city": "西安",
     *        "isp": "联通"
     *      },
     *      "RSL": [                //调用返回结果
     *        {
     *          "RS": {
     *            "code": "3",
     *            "desc": "(24,+)"
     *          },
     *          "IFT": "A3"
     *        }
     *      ],
     *      "ECL": []               //如果某几个接口数据异常会在此返回2
     *    },
     *    "status": 200,
     *    "statusMsg": ""
     *  }
     *      ***********************
     *      *   03 *	(0,3]       *
     *      *   04 *	(3,6]       *
     *      *   1  * (6,12]       *
     *      *   2  * (12,24]      *
     *      *   3  * (24,+]       *
     *      *   -1 *	查无记录      *
     *      ************************
     */
    static final Rule R360_PHONE_ONLINE_TIME_RULE = new AbstractR360PhoneRule() {
        String keyname() { 'getOnLineTime' }

        Rule.Result error() { Rule.Result.R026202 }

        String code() { 'PI002' }

        String name() { '在网时长验证' }

        String desc() { '融360运营商-手机号码在网时长信息'}

        Rule.Result invokePhoneRule(String code, String desc) {
            code == '03' || code == '04' ? error() : success()
        }
    }

    /**
     *  8.2 当前状态 getCurrentStaus
     *    {
     *      "error": 200,
     *      "msg": "",
     *      "content": [
     *        {
     *          "checkStatus": "S",
     *          "message": "成功",
     *          "checkResult": {
     *            "RSL": [
     *              {
     *                "RS": {
     *                  "desc": "正常再用",
     *                  "code": "0"
     *                },
     *                "IFT": "B7"
     *              }
     *            ],
     *            "ISPNUM": {
     *              "isp": "移动",
     *              "province": "山东",
     *              "city": "青岛"
     *            },
     *            "ECL": []
     *          }
     *        }
     *      ]
     *    }
     *
     *   *************************
     *   *   0   *	正常使用       *
     *   *   1   *	停机          *
     *   *   2   *	在网但不可用    *
     *   *   3   *	不在网         *
     *   *   -1  *	查无记录       *
     *   *************************
     */
    static final Rule R360_PHONE_CURRENT_STATUS_RULE = new AbstractR360PhoneRule2() {
        String keyname() { 'getCurrentStaus' }

        Rule.Result error() { Rule.Result.R026201 }

        String code() { 'PI001' }

        String name() { '手机当前状态验证' }

        String desc() { '融360运营商-手机号码当前状态'}

        Rule.Result invokePhoneRule(String code, String desc) {
            code == '1' || code == '2' || code == '3'? error() : success()
        }
    }

    /**
     *  8.3 消费等级 getCostLevel
     *    {
     *      "content": [
     *        {
     *          "checkStatus": "S",
     *          "message": "成功",
     *          "checkResult": {
     *            "RSL": [
     *              {
     *                "RS": {
     *                  "desc": "(0,30]",
     *                  "code": "0"
     *                },
     *                "IFT": "B7"
     *              }
     *            ],
     *            "ISPNUM": {
     *              "isp": "移动",
     *              "province": "山东",
     *              "city": "青岛"
     *            },
     *            "ECL": []
     *          }
     *        }
     *      ],
     *      "status": 200,
     *      "statusMsg": ""
     *    }
     *
     *    ****************************
     *    * 0 	    *     (0,30]     *
     *    * 1 	    *     (30,60]    *
     *    * 2 	    *     (60,100]   *
     *    * 4 	    *     (200,+]    *
     *    * 101     *  	  [0,30)     *
     *    * 102     *  	  [30,120)   *
     *    * 103     *  	  [120,+)    *
     *    * -1 	    *      查无记录    *
     *    ****************************
     */
    static final Rule R360_PHONE_COST_LEVEL_RULE = new AbstractR360PhoneRule2() {
        String keyname() { 'getCostLevel' }

        Rule.Result error() { Rule.Result.R026203 }

        String code() { 'PI003' }

        String name() { '手机消费档次验证' }

        String desc() { '融360运营商-手机号码消费档次' }

        Rule.Result invokePhoneRule(String code, String desc) {
            code == '0' || code == '101' ? error() : success()
        }
    }
    /********************************************************************************
     * 8. 融360运营商信息 - END                                                       *
     ********************************************************************************/

    /********************************************************************************
     * 9. 融360个人信用报告 - START                                                    *
     ********************************************************************************/
    /**
     * getInnerCreditService
     */
    static abstract class R360InnerCreditBaseRule<T> extends AbstractR360BaseRule{
        Rule.Result invoke(Map r360Map){
            def  valMap = r360Map.get(keyname())                  as Map
            def    code = valMap?.get(R360_RESPONSE_CODE_KEY)     as String
            def content = valMap?.get(R360_RESPONSE_CONTENT_KEY)  as Map

            code == R360_RESPONSE_OK && content ? invokeInnerCreditRule(content) : success()
        }
        Rule.Result invokeInnerCreditRule(Map content){
            Object val = getVal(content, innerKeyname())
            null != val && reject(val) ? error() : (manual(val)? Rule.Result.R00001: success())
        }
        abstract boolean reject(T val)
        boolean manual(T val){ false }
        String keyname() { 'getInnerCreditService' }
        Rule.Result error() { Rule.Result.R00001 } //不通过全部转人工

        T getVal(Map valMap, String innerKeyname){
            T val = null
            List<String> keynames = innerKeyname.split('\\.')
            if(keynames){
                String lastKey = keynames.last()
                Map nextMap = valMap
                for (int i = 0 ; i < keynames.size() - 1 ; i++){
                    if (nextMap)
                        nextMap = nextMap.get(keynames[i]) as Map
                    else
                        break
                }
                val = (T) nextMap?.get(lastKey)
            }
            val
        }
        abstract String innerKeyname()
    }

    /**
     *  9.1  与紧急联系人1月均电话次数验证 - "不通过：次数 < 5, 通过：其它"
     *       0:0次；1:1-5次；2:6-10次；3:11-20次；4:20-30次；5:30次及以上；-1：缺少联系人输入信息 （统计周期为全部）
     */
    static final Rule R360_INNER_CREDIT_EMERGENCY1_TEL_FEQUENCY_RULE = new R360InnerCreditBaseRule<Integer>(){
        String code() { 'QR001' }

        String name() { '与紧急联系人1月均电话次数验证' }

        String desc() { '与紧急联系人1月均电话次数验证' }

        boolean reject(Integer val) { val == 0 || val == 1 }

        String innerKeyname() {
            'operator.tel_record.emergency1_tel_fequency'
        }
    }

    /**
     *  9.2  与催收类号码月均电话次数验证 - "不通过：次数 > 30, 通过：其它"
     *       operator.tel_record.collection_tel_fequency
     */
    @Deprecated
    static final Rule R360_INNER_CREDIT_COLLECTION_TEL_FEQUENCY_RULE = new R360InnerCreditBaseRule<Integer>(){
        String code() { 'QR002' }

        String name() { '与催收类号码月均电话次数验证' }

        String desc() { '与催收类号码月均电话次数验证' }

        boolean reject(Integer val) { val > 30 }

        String innerKeyname() {
            'operator.tel_record.collection_tel_fequency'
        }
    }


    /**
     *  9.3  与贷款类号码月均电话次数验证 - "不通过：次数 > 30, 通过：其它"
     *       operator.tel_record.loan_tel_fequency
     */
    @Deprecated
    static final Rule R360_INNER_CREDIT_LOAN_TEL_FEQUENCY_RULE = new R360InnerCreditBaseRule<Integer>(){
        String code() { 'QR003' }

        String name() { '与贷款类号码月均电话次数验证' }

        String desc() { '与贷款类号码月均电话次数验证' }

        boolean reject(Integer val) { val > 30 }

        String innerKeyname() {
            'operator.tel_record.loan_tel_fequency'
        }
    }

    /**
     * 9.4   与赌博类号码月均电话次数验证 - "不通过：次数 > 30, 通过：其它"
     *       operator.tel_record.gamble_tel_fequency
     */
    @Deprecated
    static final Rule R360_INNER_CREDIT_GAMBLE_TEL_FEQUENCY_RULE = new R360InnerCreditBaseRule<Integer>(){
        String code() { 'QR004' }

        String name() { '与贷款类号码月均电话次数验证' }

        String desc() { '与贷款类号码月均电话次数验证' }

        boolean reject(Integer val) { val > 30 }

        String innerKeyname() {
            'operator.tel_record.gamble_tel_fequency'
        }
    }

    /**
     * 9.5   与套现类号码月均电话次数验证 - "不通过：次数 > 30, 通过：其它"
     *       operator.tel_record.cashout_tel_fequency
     */
    @Deprecated
    static final Rule R360_INNER_CREDIT_CASHOUT_TEL_FEQUENCY_RULE = new R360InnerCreditBaseRule<Integer>(){
        String code() { 'QR005' }

        String name() { '与套现类号码月均电话次数验证' }

        String desc() { '与套现类号码月均电话次数验证' }

        boolean reject(Integer val) { val > 30 }

        String innerKeyname() {
            'operator.tel_record.cashout_tel_fequency'
        }
    }

    /**
     * 9.6   与假证类号码月均电话次数验证 - "不通过：次数 > 30, 通过：其它"
     *       operator.tel_record.fakepaper_tel_fequency
     */
    @Deprecated
    static final Rule R360_INNER_CREDIT_FAKEPAPER_TEL_FEQUENCY_RULE = new R360InnerCreditBaseRule<Integer>(){
        String code() { 'QR006' }

        String name() { '与假证类号码月均电话次数验证' }

        String desc() { '与假证类号码月均电话次数验证' }

        boolean reject(Integer val) { val > 30 }

        String innerKeyname() {
            'operator.tel_record.fakepaper_tel_fequency'
        }
    }


    static abstract class R360InnerCreditDangerRule extends R360InnerCreditBaseRule<List<Map>>{
        boolean reject(List<Map> lst) {
            Date now = new Date()
            lst && lst.any {
                record ->
                    String updateTime = record?.get(updateTimeKeyname())
                    isInAnYearPeriod(now, parseDate(updateTime, FULL_DATE_FORMAT_1)) && andReject(record)
            }
        }

        String updateTimeKeyname(){ 'update_tm' }

        boolean andReject(Map val){ true }
    }
    /**
     * 9.7   是否有过M3以上借贷逾期的客户名单 - "不通过：近1年有命中名单, 通过：其它"
     */
    static final Rule R360_INNER_CREDIT_M3_OVERDUE_RULE = new R360InnerCreditDangerRule(){
        String code() { 'DL001' }

        String name() { '借贷逾期验证' }

        String desc() { '是否有过M3以上借贷逾期的客户名单' }

        String innerKeyname() {
            'danger.overdue'
        }

        Rule.Result error(){ Rule.Result.R026401 }
    }

    /**
     * 9.8   是否有过信用卡逾期记录的名单 - "不通过：近1年有命中名单 且金额大于0, 通过：其它"
     */
    static final Rule R360_INNER_CREDIT_CREDIT_OVERDUE_RULE = new R360InnerCreditDangerRule(){
        String code() { 'DL002' }

        String name() { '信用卡逾期记录的名单验证' }

        String desc() { '是否有过信用卡逾期记录的名单' }

        @Override
        boolean andReject(Map val) {
            String overdueAmountStr = val?.get('overdue_amount')
            Matcher matcher = overdueAmountStr =~ /(\d+)/
            matcher.find() && Double.parseDouble(matcher.group(1)) > 0
        }

        String innerKeyname() {
            'danger.creditcard_overdue'
        }
    }

    /**
     * 9.9   是否借贷过程中被核实欺诈的客户名单 - "不通过：近1年有命中名单, 通过：其它"
     */
    static final Rule R360_INNER_CREDIT_FRAUD_RULE = new R360InnerCreditDangerRule(){
        String code() { 'DL003' }

        String name() { '借贷欺诈的客户名单验证' }

        String desc() { '是否借贷过程中被核实欺诈的客户名单' }

        String innerKeyname() {
            'danger.fraud'
        }

        Rule.Result error(){ Rule.Result.R026403 }
    }

    /**
     * 9.10  是否有法律失信相关记录的名单 - "不通过：近1年有命中名单,有未结清的转人工, 通过：其它"
     */
    static final Rule R360_INNER_CREDIT_COURT_DISHONEST_RULE = new R360InnerCreditDangerRule(){
        String code() { 'DL004' }

        String name() { '法律失信名单验证' }

        String desc() { '是否有法律失信相关记录的名单' }

        String innerKeyname() {
            'danger.court_dishonest'
        }

        Rule.Result error(){ Rule.Result.R026404 }
    }

    /**
     * 9.11  是否具有赌博/信用卡套现/高危网络行为/羊毛党 等不良记录的名单 - "不通过：近1年有命中名单,套现转人工, 通过：其它"
     */
    static final Rule R360_INNER_CREDIT_MISCONDUCT_RULE = new R360InnerCreditDangerRule(){
        String code() { 'DL005' }

        String name() { '不良记录验证' }

        String desc() { '是否具有赌博/信用卡套现/高危网络行为/羊毛党 等不良记录的名单' }

        String innerKeyname() {
            'danger.misconduct'
        }

//    @Override
//    boolean andReject(Map val) {
//      String misconductType = val['misconduct_type']
//      misconductType != '套现'
//    }

//    @Override
//    boolean manual(List<Map> lst) {
//      def now = new Date()
//      lst && lst.any {
//        record ->
//          def updateTime = record?.get(updateTimeKeyname()) as String
//          def misconductType = record['misconduct_type']    as String
//          misconductType == '套现' && isInAnYearPeriod(now, parseDate(updateTime, FULL_DATE_FORMAT_1))
//      }
//    }

        Rule.Result error(){ Rule.Result.R026405 }
    }

    /**
     * 9.12  是否信用记录偏低的用户名单 - "不通过：近1年有命中名单, 通过：其它"
     */
    static final Rule R360_INNER_CREDIT_LOW_CREDIT_RULE = new R360InnerCreditDangerRule(){
        String code() { 'DL006' }

        String name() { '信用记录偏低验证' }

        String desc() { '是否信用记录偏低的用户名单' }

        String innerKeyname() {
            'danger.lowcredit'
        }//TODO

        Rule.Result error(){ Rule.Result.R026406 }
    }

    /**
     * 9.13  是否贷款中介的名单 - "不通过：近1年有命中名单, 通过：其它"
     */
    static final Rule R360_INNER_CREDIT_INTERAGENT_RULE = new R360InnerCreditDangerRule(){
        String code() { 'DL007' }

        String name() { '贷款中介验证' }

        String desc() { '是否贷款中介的名单' }

        String innerKeyname() {
            'danger.interagent'
        }

        Rule.Result error(){ Rule.Result.R026407 }
    }

    /**
     * 9.14  资质记录-用户借贷申请中的资质记录：近3个月搜索次数 - "不通过：近3个月搜索次数>30, 通过：其它"
     */
    @Deprecated
    static final Rule R360_INNER_CREDIT_SEARCH_CNT_LAST_3M_RULE = new R360InnerCreditBaseRule<List<Map>>(){
        String code() { 'QR014' }

        String name() { '3个月搜索次数验证' }

        String desc() { '资质记录-用户借贷申请中的资质记录：近3个月搜索次数' }

        boolean reject(List<Map> recordItems) {
            recordItems && recordItems.inject(0){
                count, record ->
                    def searchTimeStr = record['search_time'] as String
                    calcMonthPeriod(new Date(), parseDate(searchTimeStr, FULL_DATE_FORMAT_1)) <= 3 ? count + 1 : count
            } > 30
        }

        String innerKeyname() {
            'qualification.record_item'
        }
    }

    /**
     * 9.15  资质记录-用户借贷申请中的资质记录：是否有本地社保的置信度 - "不通过：本地社保的置信度<50%, 通过：其它"
     *       qualification.user_social_security
     *        - res
     *        - prob
     */
    static final Rule R360_INNER_CREDIT_USER_SOCIAL_SECURITY_RULE = new R360InnerCreditBaseRule<Map>(){
        String code() { 'CR002' }

        String name() { '本地社保的置信度验证' }

        String desc() { '资质记录-用户借贷申请中的资质记录：是否有本地社保的置信度' }

        boolean reject(Map val) {
            def res  = val['res']   as String
            def prob = val['prob']  as double
            res == '是，有本地社保' && prob < 0.5 || res == '否，没有' && prob > 0.5
        }

        String innerKeyname() {
            'qualification.qualification.user_social_security'
        }
    }

    /**
     * 9.16  资质记录-用户借贷申请中的资质记录：是否有公积金的置信度 - "不通过：公积金的置信度<50%, 通过：其它"
     *       qualification.qid77
     *       - res
     *       - prob
     */
    static final Rule R360_INNER_CREDIT_QID77_RULE = new R360InnerCreditBaseRule<Map>(){
        String code() { 'CR003' }

        String name() { '公积金的置信度验证' }

        String desc() { '资质记录-用户借贷申请中的资质记录：是否有公积金的置信度' }

        boolean reject(Map val) {
            def res  = val['res']   as String
            def prob = val['prob']  as double
            res == '是，缴纳' && prob < 0.5 || res == '否，不缴纳' && prob > 0.5
        }

        String innerKeyname() {
            'qualification.qualification.qid77'
        }
    }

    /**
     * 9.17  贷款记录-用户多次借款申请记录-天机总体：近2个月查询次数 - "不通过：近2个月查询次数>30, 通过：其它"
     *       loanrecord.tianji.stat_info.query_cnt
     */
    static final Rule R360_INNER_CREDIT_TIANJI_SEARCH_RULE = new R360InnerCreditBaseRule<Integer>(){
        String code() { 'CR004' }

        String name() { '多次借款申请记录查询次数验证' }

        String desc() { '贷款记录-用户多次借款申请记录-天机总体：近2个月查询次数' }

        boolean reject(Integer val) { val > 30 }

        String innerKeyname() {
            'loanrecord.stat_info.tj_cnt_60d'
        }

        Rule.Result error(){ Rule.Result.R026504 }
    }

    /**
     * 9.18  贷款记录-用户多次借款申请记录-天机总体：近3个月查询机构数 - "不通过：近3个月查询机构数>10, 通过：其它"
     */
    @Deprecated
    static final Rule R360_INNER_CREDIT_TIANJI_SEARCH_ORG_RULE = new R360InnerCreditBaseRule<Integer>(){
        String code() { 'CR001' }

        String name() { '多次借款申请记录查询机构数验证' }

        String desc() { '贷款记录-用户多次借款申请记录-天机总体：近3个月查询机构数' }

        boolean reject(Integer val) { val > 10 }

        String innerKeyname() {
            'loanrecord.tianji.stat_info.resource_cnt'
        }
    }

    /**
     * 9.19  贷款记录-用户多次借款申请记录-融360下单明细：融360下单明细-信用贷总量 - "不通过：信用贷总量>50000, 通过：其它"
     */
    static final Rule R360_INNER_CREDIT_ORDER_ITEM_AMOUNT_RULE = new R360InnerCreditBaseRule<List<Map>>(){
        String code() { 'CR006' }

        String name() { '融360下单明细-信用贷总量验证' }

        String desc() { '贷款记录-用户多次借款申请记录-融360下单明细：融360下单明细-信用贷总量' }

        boolean reject(List<Map> items) {
            items.inject(0L){
                sum, item -> sum + item['amount']
            } > 50000
        }

        String innerKeyname() {
            'loanrecord.record_item.order_item'
        }
    }

    /**
     * 9.20  审批记录-用户借贷审批结果记录：总体信息-审批成功率 - "不通过：审批成功率<20%, 通过：其它"
     *       approvalrecord.stat_info.approval_rate
     */
    static final Rule R360_INNER_CREDIT_APPROVAL_RATE_RULE = new R360InnerCreditBaseRule<BigDecimal>(){
        String code() { 'CR007' }

        String name() { '审批成功率验证' }

        String desc() { '审批记录-用户借贷审批结果记录：总体信息-审批成功率' }

        boolean reject(BigDecimal val) { val < 0.2 }

        String innerKeyname() {
            'approvalrecord.stat_info.approval_cnt_succ_rate'
        }

        Rule.Result error(){ Rule.Result.R026507 }
    }

    /**
     * 9.21  审批记录-用户借贷审批结果记录：总体信息-逾期率 - "不通过：逾期率>0%, 通过：其它"
     *       approvalrecord.stat_info.overdue_rate
     */
    static final Rule R360_INNER_CREDIT_OVERDUE_RATE_RULE = new R360InnerCreditBaseRule<BigDecimal>(){
        String code() { 'CR008' }

        String name() { '逾期率验证' }

        String desc() { '审批记录-用户借贷审批结果记录：总体信息-审批成功率' }

        boolean reject(BigDecimal val) { val > 0.0 }

        String innerKeyname() {
            'approvalrecord.stat_info.overdue_rate'
        }
    }

    /********************************************************************************
     * 9. 融360个人信用报告 - END                                                      *
     ********************************************************************************/

    /********************************************************************************
     * 10. 融360多头借贷 - START                                                      *
     ********************************************************************************/
    //getLongPositionCreditService
    static final Rule R360_LONG_POSITION_CREDIT_RULE = new AbstractR360BaseRule(){
        String code() { 'DT001' }
        String name() { '查询多次申请记录验证' }

        String desc() { '融360多头贷款-查询多次申请记录验证' }

        Rule.Result invoke(Map r360Map) {
            def longPositionCredit = r360Map['getLongPositionCreditService']            as Map
            def               code = longPositionCredit?.get(R360_RESPONSE_CODE_KEY)    as String
            def            content = longPositionCredit?.get(R360_RESPONSE_CONTENT_KEY) as Map

            code == R360_RESPONSE_OK && content && content['flag_applyLoan'] == "1" ?
                    Rule.Result.R00001 : success()
        }

        String keyname() { 'getLongPositionCreditService' }

        Rule.Result error() { Rule.Result.R026101}
    }
    /********************************************************************************
     * 10. 融360多头借贷 - END                                                        *
     ********************************************************************************/

    /********************************************************************************
     * 11. 融360银联智测 - START                                                      *
     ********************************************************************************/
    /**
     *  getYinLianCreditService
     */
    static abstract class R360UnionPayCreditBaseRule extends AbstractR360BaseRule{
        Rule.Result invoke(Map r360Map){
            def unionPayCredits = r360Map.get(keyname()) as List<Map>
            unionPayCredits?invokeUnionPayCreditRule(unionPayCredits):success()
        }
        abstract Rule.Result invokeUnionPayCreditRule(List<Map> unionPayCredits)

        String keyname() { 'getYinLianCreditService' }
        Rule.Result error() { Rule.Result.R00001 } //不通过全部转人工
    }

    /**
     * 11.1 个人银联检查：卡状态得分校验	-	"不通过：状态在“1或6”中的一个（多张卡必须全部都满足时才不通过）, 通过：其它"
     */
    static final Rule R360_UNION_PAY_CREDIT_CST_RULE = new R360UnionPayCreditBaseRule(){
        String code() { 'XF001' }

        String name() { '个人银联检查：卡状态得分校验' }

        String desc() { '个人银联检查：卡状态得分校验' }

        Rule.Result invokeUnionPayCreditRule(List<Map> unionPayCredits) {
            unionPayCredits.every {
                unionPayCredit ->
                    if(unionPayCredit){
                        def responseCode = unionPayCredit[R360_RESPONSE_CODE_KEY]     as String
                        def      content = unionPayCredit[R360_RESPONSE_CONTENT_KEY]  as Map
                        if(responseCode == R360_RESPONSE_OK && content){
                            def cstScore = content['cst_score'] as String
                            //1：不活跃客户
                            //2：长期忠诚客户
                            //3：活跃上升客户
                            //4：活跃下降客户
                            //5：自激活或新客户
                            //6：睡眠客户
                            return cstScore == '1' || cstScore == '6'
                        }
                    }
                    false
            }? error() : success()
        }
    }

    /**
     * 11.2 个人银联检查：套现模型得分校验	-	"不通过：得分<500 或者 得分==9990（多张卡有一张满足即不通过）, 通过：其它"
     */
    static final Rule R360_UNION_PAY_CREDIT_COT_RULE = new R360UnionPayCreditBaseRule(){
        String code() { 'XF002' }

        String name() { '个人银联检查：套现模型得分校验' }

        String desc() { '个人银联检查：套现模型得分校验' }

        Rule.Result invokeUnionPayCreditRule(List<Map> unionPayCredits) {
            unionPayCredits.any {
                unionPayCredit ->
                    if(unionPayCredit){
                        def  responseCode = unionPayCredit[R360_RESPONSE_CODE_KEY]    as String
                        def       content = unionPayCredit[R360_RESPONSE_CONTENT_KEY] as Map
                        if(responseCode == R360_RESPONSE_OK && content){
                            def cotScoreStr = content['cot_score'] as String
                            //"根据银联智策套现预测模型计算出某卡在未来6个月内发生套现的得分指标。衡量持卡人是否合理使用资金的重要指标。内容代表的含义：
                            //0~1000整数：数值越大表示套现可能越小
                            //( <300分： 高套现风险；
                            //300-499分：中高套现风险；
                            //500-699分：中套现风险；
                            //700-899分：中低套现风险；
                            //>=900分：低套现风险）
                            //9990：（特殊赋值）表示套现可能性极大
                            //9991：（特殊赋值）表示套现可能性极小"
                            if(cotScoreStr.isNumber()){
                                int cotScore = Integer.parseInt(cotScoreStr)
                                return cotScore < 500 || cotScore == 9990
                            }
                        }
                    }
                    false
            }? error() : success()
        }
    }

    /**
     * 11.3 个人银联检查：风险得分	-	"不通过：得分<500 或者 得分==9990（多张卡有一张满足即不通过）, 通过：其它"
     */
    static final Rule R360_UNION_PAY_CREDIT_RSK_RULE = new R360UnionPayCreditBaseRule(){
        String code() { 'XF003' }

        String name() { '个人银联检查：风险得分' }

        String desc() { '个人银联检查：风险得分' }

        Rule.Result invokeUnionPayCreditRule(List<Map> unionPayCredits) {
            unionPayCredits.any {
                unionPayCredit ->
                    if(unionPayCredit){
                        def responseCode = unionPayCredit[R360_RESPONSE_CODE_KEY]     as String
                        def      content = unionPayCredit[R360_RESPONSE_CONTENT_KEY]  as Map
                        if(responseCode == R360_RESPONSE_OK && content){
                            String rskStr = content['rsk_score']
                            if(rskStr.isNumber()){
                                int rsk = Integer.parseInt(rskStr)
                                return rsk < 500 || rsk == 9990
                            }
                        }
                    }
                    false
            }? error() : success()
        }
    }

    /**
     * 11.4 个人银联检查：近12个月发生交易月份数	-	"不通过：月份数<3（多张卡必须全部满足才不通过）, 通过：其它"
     */
    static final Rule R360_UNION_PAY_CREDIT_MTH_12_RULE = new R360UnionPayCreditBaseRule(){
        String code() { 'XF004' }

        String name() { '个人银联检查：近12个月发生交易月份数' }

        String desc() { '个人银联检查：近12个月发生交易月份数' }

        Rule.Result invokeUnionPayCreditRule(List<Map> unionPayCredits) {
            unionPayCredits.every {
                unionPayCredit ->
                    if(unionPayCredit){
                        def responseCode = unionPayCredit[R360_RESPONSE_CODE_KEY]    as String
                        def      content = unionPayCredit[R360_RESPONSE_CONTENT_KEY] as Map
                        if(responseCode == R360_RESPONSE_OK && content){
                            def mth12Str = content['MON_12_var1'] as String
                            return mth12Str.isNumber() && Integer.parseInt(mth12Str) < 3
                        }
                    }
                    false
            }? error() : success()
        }

        Rule.Result error() { Rule.Result.R026604 }
    }

    /**
     * 11.5 个人银联检查：近12个月笔均交易金额	-	"不通过：笔均交易金额<50（多张卡必须全部满足才不通过）, 通过：其它"
     */
    static final Rule R360_UNION_PAY_CREDIT_RFM_12_RULE = new R360UnionPayCreditBaseRule(){
        String code() { 'XF005' }

        String name() { '个人银联检查：近12个月笔均交易金额' }

        String desc() { '个人银联检查：近12个月笔均交易金额' }

        Rule.Result invokeUnionPayCreditRule(List<Map> unionPayCredits) {
            unionPayCredits.every {
                unionPayCredit ->
                    if(unionPayCredit){
                        def responseCode = unionPayCredit[R360_RESPONSE_CODE_KEY]     as String
                        def      content = unionPayCredit[R360_RESPONSE_CONTENT_KEY]  as Map
                        if(responseCode == R360_RESPONSE_OK && content){
                            def rfm12Str = content['RFM_12_var5'] as String
                            return rfm12Str.isNumber() && Double.parseDouble(rfm12Str) < 50.0
                        }
                    }
                    false
            }? error() : success()
        }

        Rule.Result error() { Rule.Result.R026605 }
    }


    /********************************************************************************
     * 11. 融360银联智测 - END                                                        *
     ********************************************************************************/

    /********************************************************************************
     * 12. 准入规则 - START                                                           *
     ********************************************************************************/
    /**
     * 12.1 年龄规则
     */
    static final Rule AGE_RULE = new Rule(){
        String code() { 'BC001' }

        String name() { '年龄验证' }

        String desc() { '基本准入规则验证-客户年龄是否在准入年龄范围内' }


        Rule.Result call(Map paramsMap) {
            String cardId = paramsMap['requestJson']['cardId']
            validate(cardId) ? Rule.Result.R0000 : Rule.Result.R021002
        }

        boolean validate(String cardId){
            cardId && cardId.length() == CHINA_MAINLAND_IDENTITY_CARD_NUM_LENGTH &&
                    calcAge(cardId) in LEGAL_AGE_RANGE
        }
    }

    /**
     * 12.2 户籍规则
     */
    static final Rule IDENTITY_CARD_RULE = new Rule(){
        String code() { 'BC002'}

        String name() { '户籍验证' }

        String desc() { '基本准入规则验证-贷款人所属户籍验证' }


        Rule.Result call(Map paramsMap) {
            def cardId = paramsMap['requestJson']['cardId'] as String
            !cardId || cardId.length() != CHINA_MAINLAND_IDENTITY_CARD_NUM_LENGTH ?
                    Rule.Result.R021001:Rule.Result.R0000
        }
    }

    /**
     * 12.3 风险高危地区规则
     */
    final static HIGH_RISK_AREA_RULE = new Rule(){
        String code() { "BC004" }

        String name() { "风险高危地区验证" }

        String desc() { "基本准入规则验证-贷款人是否属于风险高危地区" }

        Rule.Result call(Map paramsMap) {
            String cardId = paramsMap['requestJson']['cardId']
            isHighRiskArea(cardId) ? Rule.Result.R021003:Rule.Result.R0000
        }

        boolean isHighRiskArea(String cardId){
            !cardId || cardId.length() != CHINA_MAINLAND_IDENTITY_CARD_NUM_LENGTH ||
                    cardId.startsWith(HIGH_RISK_ID_PREFIX_TS) || cardId.startsWith(HIGH_RISK_ID_PREFIX_ND)
        }
    }
    /********************************************************************************
     * 12. 准入规则 - END                                                             *
     ********************************************************************************/

    /********************************************************************************
     * 13. 人行征信报告 - START                                                       *
     ********************************************************************************/
    static abstract class AbstractCreditReferenceBaseRule implements Rule{
        Rule.Result call(Map paramsMap) {
            boolean _isCreditReferenceModel = paramsMap['_isCreditReferenceModel']
            _isCreditReferenceModel?creditCheck(paramsMap):Rule.Result.R0000
        }
        abstract Rule.Result creditCheck(Map paramsMap)
    }

    /**
     *  13.0 是否存在人行征信报告 - 补充规则结果
     */
    static final Rule CREDIT_REFERENCE_EXIST_RULE = new Rule(){
        String code() { 'ZX000' }

        String name() { '是否存在人行征信报告' }

        String desc() { '是否存在人行征信报告' }

        Rule.Result call(Map paramsMap) {
            def creditRefReports = paramsMap['resultInfo'] as List<Map>         //人行报告
            def isCreditReferenceModel = isCreditReferenceModel(creditRefReports)
            paramsMap['_isCreditReferenceModel'] = isCreditReferenceModel       //是否存在人行征信报告flag存入上下文
            isCreditReferenceModel?Rule.Result.R0000:Rule.Result.R022017
        }
    }

    /**
     *  13.1 人行征信检查-五级分类包含 “关注”、“次级”、“可疑”、“损失”情况（含历史记录存在次级、可疑、损失）
     */
    static final Rule CREDIT_REFERENCE_LOAN_STATE_RULE = new AbstractCreditReferenceBaseRule(){

        String code() { 'ZX009' }

        String name() { '贷款五级分类验证'}

        String desc() { '人行征信检查-五级分类包含“关注”、“次级”、“可疑”、“损失”情况（含历史记录存在次级、可疑、损失）' }

        Rule.Result creditCheck(Map paramsMap) {
            def loanRecords = paramsMap['loanInfo'] as List<Map>
            loanRecords.any { loan ->
                def class5state = loan['class5state'] as String
                class5state ==~ /关注|次级|可疑|损失/
            } ? Rule.Result.R022012 : Rule.Result.R0000
        }
    }

    /**
     * 13.2 账户状态验证 - 不通过：账户状态中包含“担保人代偿”、“以资抵债”、“冻结”、“止付”
     */
    static final Rule CREDIT_REFERENCE_USER_STATE_RULE = new AbstractCreditReferenceBaseRule(){
        String code() { 'ZX010' }

        String name() { '账户状态验证'}

        String desc() { '人行征信检查-账户状态中包含“担保人代偿”、“以资抵债”、“冻结”、“止付”' }

        Rule.Result creditCheck(Map paramsMap) {
            def loanRecords     = paramsMap['loanInfo'] as List<Map>
            def loanCardRecords = paramsMap['loanCardInfo'] as List<Map>
            loanRecords.any {
                loan ->
                    def state = loan['state'] as String
                    state ==~ /担保人代偿|以资抵债|冻结|止付/
            } ||
                    loanCardRecords.any {
                        loanCard ->
                            def state     = loanCard['state'] as String
                            def cardType  = loanCard['card_type_cue'] as String
                            cardType ==~ /贷记卡|准贷记卡/ && state ==~ /担保人代偿|以资抵债|冻结|止付/
                    }? Rule.Result.R022013 : Rule.Result.R0000
        }
    }

    /**
     * 13.3 贷记卡、准贷记卡、贷款当前状态验证 - 存在以下情况之一的则校验不通过：
     *  1. 为"关注", 且有当前逾期，逾期金额>100元
     *  2. 有"逾期", 且逾期金额>200元
     *  3. 为"呆账"的, 但贷款呆账金额为0. 或贷记卡、准贷记卡呆账金额≤1000元的除外
     */
    static final Rule CREDIT_REFERENCE_LOAN_CARD_STATE_RULE = new AbstractCreditReferenceBaseRule(){
        String code() { 'ZX011' }

        String name() { '贷记卡、准贷记卡、贷款当前状态验证' }

        String desc() { '贷记卡、准贷记卡、贷款当前状态验证' }

        Rule.Result creditCheck(Map paramsMap) {
            def     loans = paramsMap['loanInfo']     as List<Map>
            def loanCards = paramsMap['loanCardInfo'] as List<Map>

            if(loans.any {
                loan ->
                    def class5State    = loan['class5state']        as String
                    def statusCue      = loan['account_status_cue'] as String
                    def overdueAmount  = parseLong(loan['curroverdueamount'] as String)
                    def  badBalance    = parseLong(loan['bad_balance_cue'] as String)

                    class5State == '关注' && overdueAmount > 100 ||
                            overdueAmount > 200 ||
                            statusCue == '呆账' && badBalance > 0
            })
                return Rule.Result.R022014

            if(loanCards.any{
                loanCard ->
                    def overdueAmount   = parseLong(loanCard['curroverdueamount'] as String)
                    def statusCue       = loanCard['account_status_cue'] as String
                    def cardTypeCue     = loanCard['card_type_cue'] as String
                    def badBalance      = parseLong(loanCard['bad_balance_cue'] as String)

                    overdueAmount > 200 ||
                            statusCue == '呆账' && (cardTypeCue == '贷记卡' || cardTypeCue == '准贷记卡') && badBalance > 1000
            })
                return Rule.Result.R022014

            Rule.Result.R0000
        }
    }

    /**
     *  13.4 人行征信检查-信贷审批查询记录明细中
     *  近三个月内人行征信报告查询次数6次及以上， 查询原因为信用卡审批或贷款审批的。
     */
    static final Rule CREDIT_REFERENCE_SEARCH_RECORD_RULE = new AbstractCreditReferenceBaseRule() {
        String code() { 'ZX012' }

        String name() { '征信报告查询次数验证' }

        String desc() { '''人行征信检查-信贷审批查询记录明细中
                        近三个月内人行征信报告查询次数6次及以上，
                        查询原因为信用卡审批或贷款审批的。'''.stripIndent() }

        Rule.Result creditCheck(Map paramsMap) {
            def searchRecords = paramsMap['recordDetail'] as List<Map>
            searchRecords.findAll { searchRecord ->
                def queryReason  = searchRecord['queryreason']  as String
                def queryDateStr = searchRecord['querydate']    as String
                Matcher matcher = queryReason =~ /信用卡|贷款/
                if(matcher.find()){
                    Date queryDate = null
                    try{
                        queryDate = Date.parse(FULL_DATE_FORMAT_2, queryDateStr)
                    }catch (ignored){
                        try{
                            queryDate = Date.parse(SIMPLE_DATE_FORMAT, queryDateStr)
                        }catch (ignored2){}
                    }
                    return queryDate && (new Date() - queryDate) <= MAX_CREDIT_REFERENCE_SEARCH_RECORD_DURATION_INCLUSIVE
                }
                false
            }.size() >= MAX_CREDIT_REFERENCE_SEARCH_RECORD_NUM_INCLUSIVE ?
                    Rule.Result.R022008 : Rule.Result.R0000
        }
    }

    /**
     * 13.5 申请用户姓名、身份证号、手机号码与征信报告中信息是否一致
     */
    static final Rule CREDIT_REFERENCE_PERSONAL_INFO_RULE = new AbstractCreditReferenceBaseRule() {
        String code() { 'ZX013' }

        String name() { '申请者三要素与征信报告中信息是否一致' }

        String desc() { '人行征信检查-申请用户姓名、身份证号、手机号码与征信报告中信息是否一致' }

        Rule.Result creditCheck(Map paramsMap) {
            def cardId = paramsMap['cardId']    as String
            def   name = paramsMap['realName']  as String
            def  phone = paramsMap['phone']     as String
            def personalInfoList = paramsMap['personalInfo'] as List<Map>
            boolean match = personalInfoList.any {
                personalInfo ->
                    cardId == personalInfo['cardno'] &&
                            name == personalInfo['customername'] &&
                            phone == personalInfo['mobile']
            }
            // flag放到上下文中，鹏元信息验证需要此flag
            paramsMap['_isPersonalInfoMatch'] = match

            match ? Rule.Result.R0000 : Rule.Result.R022016
        }
    }

    /**
     * 13.6 人行征信报告评分验证
     */
    static final Rule CREDIT_REFERENCE_SCORE_MODEL_RULE = new Rule(){
        String code() { 'ZX999' }

        String name() { '人行征信报告评分验证' }

        String desc() { '人行征信报告评分验证' }

        Rule.Result call(Map paramsMap) {
            def results       = paramsMap['resultInfo']     as List<Map>
            def infoSummaries = paramsMap['creditSummary']  as List<Map>
            def loans         = paramsMap['loanInfo']       as List<Map>
            def loanCards     = paramsMap['loanCardInfo']   as List<Map>
            def personalInfos = paramsMap['personalInfo']   as List<Map>
            def firstInfoSummary    = infoSummaries?infoSummaries.first():null as Map
            def firstResult         = results?results.first():null as Map

            def _isCreditReferenceModel = paramsMap['_isCreditReferenceModel'] as boolean
            if(!_isCreditReferenceModel){
                paramsMap.put('_creditScore', 9999D)
                return Rule.Result.R022017
            }

            def houseLoanCount          = parseLong(firstInfoSummary['house_loan_count'] as String)
            def businessHouseLoanCount  = parseLong(firstInfoSummary['busi_house_loan_count'] as String)
            def otherLoanCount          = parseLong(firstInfoSummary['other_loan_count'] as String)
            def loanCardCount           = parseLong(firstInfoSummary['loan_card_count'] as String)
            def standLoanCardCount      = parseLong(firstInfoSummary['standard_loan_card_count'] as String)
            def assureRepayCount        = parseLong(firstInfoSummary['assure_repay_count'] as String)

            if(houseLoanCount > 0 ||
                    businessHouseLoanCount > 0 ||
                    otherLoanCount > 0         ||
                    loanCardCount > 0          ||
                    standLoanCardCount > 0     ||
                    assureRepayCount > 0){
                paramsMap.put('_availableToCalcCreditScore', true) //人行评分模型计算条件flag 存入上下文
            }else{
                paramsMap.put('_creditScore', 9999D)
                paramsMap.put('_availableToCalcCreditScore', false) //人行评分模型计算条件flag 存入上下文
                return Rule.Result.R022018
            }

            double score = CREDIT_REFERENCE_DEFAULT_SCORE

            // 1. 首张信用卡发卡到现在的月数
            score += calcScoreByFirstCredit(firstResult['report_create_time'] as String, firstInfoSummary['first_loan_card_open_month'] as String)

            // 2. 贷记卡账户数 (信用卡数量)
            score += calcScoreByCreditCardCoun(firstInfoSummary['loan_card_count'] as String)

            // 3. 信用卡余额占授信额比例>30%的账户数
            score += calcScoreByCreditRemainder(loanCards)

            // 4. 住房贷款总额
            score += calcScoreByHouseLoan(loans)

            // 5. 未结清且没有逾期记录的贷款的最大账龄
            score += calcScoreByMaxLoanDebt(loans, firstResult['report_create_time'] as String)

            // 6. 未结清贷款最高剩余还款期数
            score += maxLoanDebtPeriods(loans)

            // 7. 未结清贷款，本月应还款之和
            score = score + sumOfLoanMonthDebt(loans)

            // 8. PBOC学历
            score = score + educationScore(personalInfos)

            paramsMap.put('_creditScore', score)
            // 分数判断
            switch (score){
                case {score <= 530}:
                    return Rule.Result.R022003
                case {score > 530 && score <= 561}:
                    return Rule.Result.R00001
                default:
                    return Rule.Result.R0000
            }
        }

        /**
         *
         * @param reportDateStr 报告生成时间(yyyy.MM.dd HH:mm:ss)
         * @param firstLoanCardYearMonthStr 首张准贷记卡发卡月份(yyyy.MM)
         * @return
         */
        double calcScoreByFirstCredit(String reportDateStr, String firstLoanCardYearMonthStr){
            SCORE_CARD__FIRST_CREDIT.getScore(
                    calcMonthPeriod(
                            parseDate(reportDateStr, FULL_DATE_FORMAT_2),
                            parseDate(firstLoanCardYearMonthStr, YEAR_MONTH_DATE_FORMAT))
            )
        }
        /**
         *
         * @param creditCardCountStr 贷记卡账户数
         * @return
         */
        double calcScoreByCreditCardCoun(String creditCardCountStr){
            long creditCardCount = creditCardCountStr && creditCardCountStr.isNumber() ?
                    Math.round(creditCardCountStr.toDouble()) : Long.MIN_VALUE
            SCORE_CARD__CREDIT_COUNT.getScore(creditCardCount)
        }

        /**
         * 信用卡余额占授信额比例>30%的账户数
         * @param loadCards
         * @return
         */
        double calcScoreByCreditRemainder(List loadCards){
            // 没有信用卡 或者 所有信用卡都未激活，按照默认值算
            if(!loadCards || loadCards && loadCards.every {
                loadCard -> "未激活" == loadCard['state']
            }){
                return SCORE_CARD__CREDIT_REMAINDER.getDefaultScore()
            }else{
                int accountNum = loadCards.inject(0){
                    count, loadCard ->
                        String state                  = loadCard['state']
                        String creditLimitAmountStr   = loadCard['creditlimitamount_cue']
                        String loanCardUsedCreditStr  = loadCard['undestyryloancard_used_credit']
                        //授信额度
                        long creditLimitAmount = parseLong(creditLimitAmountStr)?:-1
                        //已用额度
                        long loanCardUsedCredit= parseLong(loanCardUsedCreditStr)?:-1

                        "未激活" != state && "销户" != state &&
                                ((creditLimitAmount - loanCardUsedCredit)/(creditLimitAmount * 1D) > 0.3) ?
                                count + 1 : count
                } as int
                return SCORE_CARD__CREDIT_REMAINDER.getScore(accountNum)
            }
        }

        /**
         * 住房贷款总额
         * @param loans
         * @return
         */
        double calcScoreByHouseLoan(List loans){
            SCORE_CARD__HOUSE_LOAN.getScore(loans.inject(0L){
                sum, loan ->
                    def loanType       = loan['loan_type_cue']    as String
                    def loanAmountStr  = loan['loan_amount_cue']  as String
                    "个人住房贷款" == loanType ?
                            sum + parseLong(loanAmountStr) : sum
            } as Long)
        }

        /**
         * 未结清且没有逾期记录的贷款的最大账龄
         * @param loans
         * @param reportCreatedDateStr
         * @return
         */
        double calcScoreByMaxLoanDebt(List loans, String reportCreatedDateStr){
            try{
                int maxOverdueMonthPeriod = loans.inject(0){
                    maxMonth, loan ->
                        String latest24state = loan['latest24state']       // 24个月的还款状态
                        long         balance = parseLong(loan['balance'] as String)  // 本金余额
                        String  startDateStr = loan['start_date_cue']      // 发放日期

                        if(balance > 0 && // 本金余额大于0
                                !(latest24state =~ /[1-7c]/).find()) { // 不含逾期期贷款
                            Date         date1 = parseDate(startDateStr, SIMPLE_DATE_FORMAT)
                            Date         date2 = parseDate(reportCreatedDateStr, FULL_DATE_FORMAT_2)
                            def    monthPeriod = calcMonthPeriod(date2, date1)
                            if(monthPeriod > maxMonth)
                                return monthPeriod
                        }

                        maxMonth
                }
                return SCORE_CARD__MAX_LOAN_DEBT_DATE.getScore(maxOverdueMonthPeriod)
            }catch(ignored){
            }

            SCORE_CARD__MAX_LOAN_DEBT_DATE.getDefaultScore()
        }

        /**
         * 未结清贷款最高剩余还款期数
         * @param loans
         * @return
         */
        double maxLoanDebtPeriods(List<Map> loans){
            def maxPeriod = loans.inject(0L){
                max, loan ->
                    def balance = parseLong(loan?.get('balance') as String)                   as long
                    def remainpaymentcyc = parseLong(loan?.get('remainpaymentcyc') as String) as long
                    balance > 0 && remainpaymentcyc > max ? remainpaymentcyc : max
            } as long
            SCORE_CARD__LOAN_DEBT_PERIODS.getScore(maxPeriod)
        }

        /**
         * 本月应还款之和
         * @param loans
         * @return
         */
        double sumOfLoanMonthDebt(List<Map> loans){
            !loans?
                    SCORE_CARD__SUM_OF_LOAN_MONTH_DEBT.getDefaultScore():
                    SCORE_CARD__SUM_OF_LOAN_MONTH_DEBT.getScore(
                            loans.inject(0L){
                                sum, loan ->
                                    long amount = parseLong(loan?.get('scheduledpaymentamount') as String)
                                    sum + amount
                            } as long
                    )
        }

        /**
         * 学历信息
         * @param personalInfos
         * @return
         */
        double educationScore(List<Map> personalInfos){
            double score = 7.19
            if(personalInfos){
                Map personalInfo = personalInfos.first()
                String edu_level = personalInfo?.get("edu_level")
                switch (edu_level){
                    case '研究生':
                        score = 34.73
                        break
                    case '高中':
                    case '大学本科（简称"大学"）' :
                        score = 13.58
                        break
                    case '大学专科和专科学校（简称"大专"）':
                    case '技术学校':
                    case '中等专业学校或中等技术学校':
                        score = -3.28
                        break
                    case '文盲或半文盲':
                    case '小学':
                    case '初中':
                        score = -17.17
                        break
                    default: 7.19
                }
            }
            score
        }
    }

    /********************************************************************************
     * 13. 人行征信报告 - END                                                         *
     ********************************************************************************/

    /********************************************************************************
     * 14. 授信额度模型 - START                                                       *
     ********************************************************************************/
    static long calcCashQuota(Map paramsMap){
        DEFAULT_CASH_QUOTA
    }

    static long calcQuota(Map paramsMap){
        def creditindex        = paramsMap['creditindex']              as Map          //本地数据库拉取大数据指标
        def creditSummary      = paramsMap['creditSummary']            as List<Map>    //
        def loanCards          = paramsMap['loanCardInfo']             as List<Map>    //本地数据库贷记卡信息
        def loans              = paramsMap['loanInfo']                 as List<Map>    //本地数据库贷记卡信息
        def unionPayCredits    = paramsMap['r360Info']?.get('getYinLianCreditService')  as List<Map>    //实时查询大数据银联智测
        //  *1）月生活日常开销（总的平台流水类（消费）)
        def customConsumePerMth   = getFactor(creditindex, 'day_expenses_1m', 0.0)//Double.parseDouble((creditindex?.get('day_expenses_1m') as String)?:'0')
        //  *2）消费支出稳定系数：月生活日常开销
        //  (设：近12个月的月CV)
        //        下限	上限       系数
        //        0	    1         1.1
        //        1	    2         1
        //        2	    3         0.9
        //        3	    99999	  0.8
        def consumeStableFactor   = getFactor(creditindex, 'cons_exp_coeff', 1.0)//Double.parseDouble((creditindex?.get('cons_exp_coeff') as String)?:'1.1')
        //  *3）信用风险系数
        //  （有人行数据，取人行模型系数；无人行数据时，取存量模型系数）
        //    ①人行模型
        //        分数段	        风险等级	系数
        //        <491 或 9999	  C	      0.4
        //        492-509	      BB	    0.6
        //        510-531	      A	      1
        //        532-561	      AA 	    1.1
        //        562-800	      AAA	    1.3
        //    ②存量模型(现有的大数据会员贷预授信计算的模型得出的分数，不是商品分期一期的模型评分）
        //        分数段	        风险等级	系数
        //        <578	        C	      0.4
        //        579-606	      BB	    0.6
        //        607-622	      A	      1
        //        623-639	      AA 	    1.1
        //        640-800	      AAA	    1.3
        def creditRiskFactor      = calcCreditRiskFactor(paramsMap)

        //  *4）月投资收益
        //    ①日均计算口径：为近365天的资产相加/实际持有的天数（单位：元）
        //    ②资产类型：近12个月日均基金预期收益*30
        //              近12个月日均零钱宝预期收益*30
        def incomePerMth          = getFactor(creditindex, 'invest_income_1m', 0.0)//Double.parseDouble((creditindex?.get('invest_income_1m') as String)?:'0')

        //  *5）月投资支出 （人行）未结清贷款信息汇总(明细）->本月实还款
        //    投资支出类型：人行报告中，当月应还款金额
        def expensePerMth         = paramsMap['_isCreditReferenceModel']?calcExpensePerMth(loans, loanCards):0D

        //  *6）收入流水稳定系数
        //    （内外部员工，有已知数据时，采用下列系数规则；无数据时，直接取1）
        def incomeStatableFactor  = getFactor(creditindex, 'income_sta_factor', 1.0)//Double.parseDouble((creditindex?.get('income_sta_factor') as String)?:'1')

        //  *7）资产价值增长系数（无人行征信数据时，固定资产类系数=1）
        def assetIncreasementFactor = calcAssetIncrementFactor(paramsMap)

        //  *8）负债稳定系数
        //  负债类：易付宝信用卡还款金额(设：近12个月每个月的负债金额为x1,x2,…,x12)
        //  12月的标准差/均值=CV
        //      下限	  上限	系数
        //      0	  0.1	1.1
        //      0.1	  1.5	1
        //      1.5	  3	    0.9
        //      3	  99999	0.8
        //  区间取左开，右闭
        def debtStableFactor    = getFactor(creditindex, 'debt_sta_factor', 1.0) //Double.parseDouble((creditindex?.get('debt_sta_factor') as String)?:'1.1')

        double customConsumePerMthTotal = customConsumePerMth +
                calcCustomConsumePerMthOutData(creditSummary, loanCards, unionPayCredits)

        long quotaBase =  ((customConsumePerMthTotal +                          //月生活日常开销
                incomePerMth -                                    //月投资收益（标签数据）
                expensePerMth) * 12 ) *                           //月投资支出 (根据人行报告计算）
                consumeStableFactor *                             //消费支出稳定系数（标签数据）
                incomeStatableFactor *                            //收入流水稳定系数（标签数据）
                assetIncreasementFactor *                         //资产价值增长系数
                debtStableFactor *                                //负债稳定系数（标签数据）
                creditRiskFactor                                  //信用风险系数

        def _isCreditReferenceModel     = paramsMap['_isCreditReferenceModel']      as boolean
        def _availableToCalcCreditScore = paramsMap['_availableToCalcCreditScore']  as boolean
        def _isExistingUserModel        = paramsMap['_isExistingUserModel']         as boolean

        if(_isCreditReferenceModel && _availableToCalcCreditScore || _isExistingUserModel)
            return calcQuota(quotaBase, NON_BLANK_USER_CONSUMPTION_MIN_QUOTA, NON_BLANK_USER_CONSUMPTION_MAX_QUOTA)
        else
            return calcQuota(quotaBase, BLANK_USER_CONSUMPTION_MIN_QUOTA, BLANK_USER_CONSUMPTION_MAX_QUOTA)
    }

    static long calcQuota(long quotaBase, long min, long max){
        long formulaResult = Math.ceil(quotaBase / 500) * 500
        switch (formulaResult){
            case {it < min}:
                return min
            case {it > max}:
                return max
            default:
                return formulaResult
        }
    }

    //    投资支出类型：人行报告中，当月应还款金额
    static double calcExpensePerMth(List<Map> loans, List<Map> loanCards){
        def amount = 0.0
        if(loans)
            amount += loans.sum {
                loan ->
                    Double.parseDouble((loan['scheduledpaymentamount'] as String)?:'0')
            }
        if(loanCards)
            amount += loanCards.sum {
                loanCard ->
                    Double.parseDouble((loanCard['scheduledpaymentamount'] as String)?:'0')
            }
        amount
    }

    static double calcAssetIncrementFactor(Map paramsMap){
        //  *7）资产价值增长系数（无人行征信数据时，固定资产类系数=1）
        //    资产价值增长系数=流动资产类*固定资产类（已还清房贷*已还清车贷*未还清房贷）
        //  ①流动资产类：易付宝和零钱宝余额(设：近12个月每个月的余额为x1,x2,…,x12)
        //      12月的标准差/均值=CV
        //      下限	    上限	    系数
        //      0	    0.6	    1.2
        //      0.6	    1.5	    1
        //      1.5	    3	    0.9
        //      3	    99999	0.8
        //      区间取左开，右闭
        //  ②固定资产类：人行征信报告数据
        //      分类	              下限	上限	    系数
        //      "已还清房贷          0	0	    0.8
        //      （房屋数）"          1	9999	1.2
        //
        //      "已还清车贷          0	0	    0.8
        //      （车辆数）"          1	9999	1.1
        //
        //      "未还清房贷          0	0	    0.8
        //      （房屋数）"	          1	2	    1.1
        //                          2	9999	1.2
        def currentAssetFactor = getFactor(paramsMap['creditindex'], 'assets_factor', 1.0)//Double.parseDouble((paramsMap['creditindex']?.get('assets_factor') as String)?:'1.2')
        def loans              = paramsMap['loanInfo'] as List<Map>    //本地数据库贷记卡信息
        def assetFactor        = 1.0 as double

        if(paramsMap['_isCreditReferenceModel']){
            def houseLoanCount = loans?loans.sum {
                loan -> loan['state'] == '结清' && loan['cue']?.contains('房贷') ? 1 : 0
            }:0 as int

            def carLoanCount = loans?loans.sum {
                loan -> loan['state'] == '结清' && loan['cue']?.contains('车贷') ? 1 : 0
            }:0 as int

            def normalHouseLoanCount = loans?loans.sum {
                loan -> loan['state'] == '正常' && loan['cue']?.contains('房贷') ? 1 : 0
            }:0 as int

            def houseLoanFactor         = (houseLoanCount == 0 ? 0.8 : 1.2) as double
            def carLoanFactor           = (carLoanCount == 0 ? 0.8 : 1.1) as double
            def normalHouseLoanFactor   = (normalHouseLoanCount == 0 ? 0.8 :
                    (normalHouseLoanCount ==1 || normalHouseLoanCount ==2) ? 1.1 : 1.2 ) as double

            assetFactor = houseLoanFactor * carLoanFactor * normalHouseLoanFactor
        }

        currentAssetFactor * assetFactor
    }

    static double calcCreditRiskFactor(Map paramsMap){
        if(paramsMap['_isCreditReferenceModel']){
            def creditReferenceScore = paramsMap['_creditScore'] as double
            switch (creditReferenceScore){
                case {it < 491 || it == 9999}:
                    return 0.4
                case {it >= 492 && it <= 509}:
                    return 0.6
                case {it >= 510 && it <= 531}:
                    return 1.0
                case {it >= 532 && it <= 561}:
                    return 1.1
                case {it >= 562 && it <= 800}:
                    return 1.3
                default:
                    return 1.3
            }
        }else{
            return getFactor(paramsMap['creditindex'] as Map, 'cre_risk_coeff', 0.4)//Double.parseDouble((paramsMap['creditindex']?.get('cre_risk_coeff') as String)?:'0.4')
        }
    }

    static double calcCustomConsumePerMthOutData(
            List<Map> creditSummary,
            List<Map> loanCards,
            List<Map> unionPayCredits
    ){
        double val = 0.0
        if(creditSummary && creditSummary.first()) {
            val += Double.parseDouble((creditSummary.first()?.get('undestyryloancard_used_avg_6') as String)?:"0") +
                    Double.parseDouble((creditSummary.first()?.get('undestyrysloancard_used_avg_6') as String)?:"0")
        }

        if(loanCards){
            val += loanCards.inject(0.0){
                amount, loanCard ->
                    if(loanCard){
                        String         state = loanCard['state']
                        String      cardType = loanCard['card_type_cue']
                        String sixmAvgAmount = loanCard['latest6monthusedavgamount']?:'0'
                        if('正常' == state && ('货记卡' == cardType || '准货记卡' == cardType)){
                            return amount + Double.parseDouble(sixmAvgAmount)
                        }
                    }
                    amount
            }
        }

        if(unionPayCredits){ //银联的账单报告
            val += unionPayCredits.inject(0.0){
                amount, unionPay ->
                    if(unionPay){
                        def code    = unionPay[R360_RESPONSE_CODE_KEY]    as String
                        def content = unionPay[R360_RESPONSE_CONTENT_KEY] as Map
                        if(code == R360_RESPONSE_OK && content){
                            String dcFlag = content['dc_flag']
                            if('debit' ==  dcFlag || 'credit' == dcFlag){ //debit代表借记卡， credit代表信用卡
                                return amount + Double.parseDouble((content['RFM_12_var1']?:'0') as String) //近12个月交易金额
                            }
                        }
                    }
                    amount
            }/12 //信用卡&借记卡近12个月交易金额/12
        }
        val
    }

    static double getFactor(Map paramMap, String key, double defaultValue){
        def factor = 0.0
        try{
            factor = Double.parseDouble(paramMap?.get(key))
        }catch(ignored){
        }
        factor > 0 ? factor : defaultValue
    }
    /********************************************************************************
     * 14. 授信额度模型 - END                                                         *
     ********************************************************************************/

    /********************************************************************************
     * 15. 存量用户评分 - START                                                       *
     ********************************************************************************/
    static final Rule EXISTING_USER_MODEL_RULE = new Rule() {
        String code() { 'CL001' }

        String name() { '存量用户平分模型校验' }

        String desc() { '存量用户平分模型得分' }

        Rule.Result call(Map paramsMap) {
            def creditindex = paramsMap['creditindex'] as Map
            boolean isExistingUserModel = isExistingUserModel(creditindex)

            def _isCreditReferenceModel     = paramsMap['_isCreditReferenceModel'] as boolean
            def _availableToCalcCreditScore = paramsMap['_availableToCalcCreditScore'] as boolean

            if((!_isCreditReferenceModel || !_availableToCalcCreditScore) && isExistingUserModel){
                paramsMap['_isExistingUserModel'] = true //将存量用户flag存入上下文
            }else{
                paramsMap['_isExistingUserModel'] = false //将存量用户flag存入上下文
                paramsMap['_existingUserModelScore'] = 9999D
                return Rule.Result.R023003
            }

            double score = EXISTING_USER_MODEL_DEFAULT_SCORE

            // 1.注册时间距当前时间月份数
            score += SCORE_CARD__REGISTRY_MONTH_DURATION.getScore(
                    calcMonthPeriod(new Date(), parseDate(creditindex['cfc_rgst_dt'] as String, FULL_DATE_FORMAT_1))
            )

            // 2.工作时间访问偏好评分
            score += calcScoreByWorktimeVisitPreference(creditindex['work_tm_vst_lvl'] as String)

            // 3.近12个月的访问次数评分卡
            score += SCORE_CARD__VISIT_COUNT_IN_12M.getScore(
                    parseLong(creditindex['vst_cnt_12m'] as String)
            )

            // 4.过去30天基金日均持有份额评分卡
            score += SCORE_CARD__FND_AVG_HOLD_AMT_1M.getScore(
                    parseLong(creditindex['fnd_avg_hold_amt_1m'] as String)
            )

            // 5.近一个月易付宝绑定银行卡数量
            score += SCORE_CARD__BIND_NUM_1M.getScore(
                    parseLong(creditindex['bind_num_1m'] as String)
            )

            // 6.线上整体活跃度标签评分卡
            score += SCORE_CARD__ACTV_RFR_ON.getScore(
                    parseLong(creditindex['actv_rfr_on'] as String)
            )

            // 7.近一个月线上平均购买金额评分卡
            score += SCORE_CARD__BUY_AMT_ONLINE_1M.getScore(
                    parseLong(creditindex['buy_amt_online_1m'] as String)
            )

            // 8.过去60天申购的基金金额总和评分卡
            score += SCORE_CARD__FND_PRCH_CNT_2M.getScore(
                    parseLong(creditindex['fnd_prch_cnt_2m'] as String)
            )

            // 9.近一个月是否购买过3C
            score += SCORE_CARD__IS_3C_BUYER_1M.getScore(
                    parseLong(creditindex['is_3c_buyer_1m'] as String)
            )

            // 10.是否大家电买家群
            score += SCORE_CARD__IS_DJD_BUYER.getScore(
                    parseLong(creditindex['is_djd_buyer'] as String)
            )

            // 11.生命周期
            score += SCORE_CARD__LIFE_CYCLE.getScore(
                    parseLong(creditindex['life_cycle'] as String)
            )

            // 12.忠诚度
            score += SCORE_CARD__LOYALTY_LEVEL.getScore(
                    parseLong(creditindex['loyalty_level'] as String)
            )

            // 13.最早的基金申请时间距离当前时间距离当前时间的月数
            score += SCORE_CARD__FIRST_FND_DT.getScore(
                    calcMonthPeriod(new Date(), parseDate((creditindex['first_fnd_dt'] as String) + ' 00:00:01', FULL_DATE_FORMAT_1))
            )

            // 14.近12个月内不同的订单不同的送货地址数目
            score += SCORE_CARD__ORD_ADDR_CNT_12M.getScore(
                    parseLong(creditindex['ord_addr_cnt_12m'] as String)
            )

            paramsMap['_existingUserModelScore'] = score

            if(score < 660.0d)
                return Rule.Result.R023001
            else if(score >= 660.0d && score <= 700.0d)
                return Rule.Result.R00001
            else
                Rule.Result.R0000
        }

        /**
         * 2.工作时间访问偏好评分
         */
        static double calcScoreByWorktimeVisitPreference(String workTmVstLvl){
            if (workTmVstLvl) {
                switch (workTmVstLvl){
                    case '及其偏好':
                        return 67.0
                    case '偏好':
                        return 52.0
                    case '一般':
                        return 43.0
                    case '无偏好':
                        return 36.0
                    case '轻微':
                        return 35.0
                    case '其他':
                    default:
                        return 16.0
                }
            }
            16.0
        }
    }

    /********************************************************************************
     * 15. 存量用户评分 - END                                                         *
     ********************************************************************************/

    /********************************************************************************
     * 16. 白板用户规则 - START                                                       *
     ********************************************************************************/
    /**
     * 16.1 白板用户年龄规则
     */
    static final Rule BLANK_USER_MODEL_AGE_RULE = new Rule() {
        String code() { 'BB005' }

        String name() { '白板用户年龄规则' }

        String desc() { '白板用户年龄规则' }

        Rule.Result call(Map paramsMap) {
            def _isCreditReferenceModel     = paramsMap['_isCreditReferenceModel']      as boolean
            def _availableToCalcCreditScore = paramsMap['_availableToCalcCreditScore']  as boolean
            def _isExistingUserModel        = paramsMap['_isExistingUserModel']         as boolean

            if(_isCreditReferenceModel && _availableToCalcCreditScore || _isExistingUserModel)
                return Rule.Result.R0000

            def cardId = paramsMap['requestJson']['cardId'] as String
            boolean validate = cardId && cardId.length() == CHINA_MAINLAND_IDENTITY_CARD_NUM_LENGTH &&
                    calcAge(cardId) in BLANK_USER_MODEL_LEGAL_AGE_RANGE
            validate?Rule.Result.R0000:Rule.Result.R024006
        }
    }

    /********************************************************************************
     * 16. 白板用户规则 - END                                                         *
     ********************************************************************************/

    /*********************************************************************************
     * 17. 风险特征规则 - START
     *********************************************************************************/
    static abstract class AbstractCSIRFDSBaseRule implements Rule{
        abstract String keyName()

        Rule.Result reject(){Rule.Result.R00001}

        Rule.Result approve(){ Rule.Result.R0000 }

        Rule.Result invoke(def val){
            Integer.valueOf(val==null?'0':val)>1?reject():approve()
        }

        Rule.Result call(Map paramsMap){
            def riskFeatureInfo = paramsMap["riskFeatureInfo"] as Map
            def val = riskFeatureInfo?.get(keyName())
            invoke(val)
        }
    }

    /**
     * 3个月内绑定手机号码成功申请的次数
     *    不通过：次数>1
     *    通过：其它情况为通过
     */
    static final Rule CSIRFDS_RULE_1 = new AbstractCSIRFDSBaseRule(){
        String code() { "ZB001" }

        String name() { "3个月内绑定手机号码成功申请的次数" }

        String desc() { "3个月内绑定手机号码成功申请的次数" }

        String keyName() {'CSIRCDS_INDEX_123'}
    }
    /**
     * 3个月内直系亲属电话+直系亲属姓名成功申请次数
     *   *    不通过：次数>1
     *    通过：其它情况为通过
     */
    static final Rule CSIRFDS_RULE_2 = new AbstractCSIRFDSBaseRule(){
        String code() { "ZB002" }

        String name() { "3个月内直系亲属电话+直系亲属姓名成功申请次数"}

        String desc() { "3个月内直系亲属电话+直系亲属姓名成功申请次数" }

        String keyName() {'CSIRCDS_INDEX_124'}
    }
    /**
     * 3个月内直系亲属电话作为绑定手机号码成功申请的次数
     * *    不通过：次数>1
     *    通过：其它情况为通过
     */
    static final Rule CSIRFDS_RULE_3 = new AbstractCSIRFDSBaseRule(){
        String code() { "ZB003" }

        String name() { "3个月内直系亲属电话作为绑定手机号码成功申请的次数" }

        String desc() { "3个月内直系亲属电话作为绑定手机号码成功申请的次数" }

        String keyName() {'CSIRCDS_INDEX_125'}
    }
    /**
     * 3个月内成功申请的同一直系亲属姓名，直系亲属电话不同的次数
     *   不通过：次数>1
     *    通过：其它情况为通过
     */
    static final Rule CSIRFDS_RULE_4 = new AbstractCSIRFDSBaseRule(){
        String code() { "ZB004" }

        String name() { "3个月内成功申请的同一直系亲属姓名，直系亲属电话不同的次数" }

        String desc() { "3个月内成功申请的同一直系亲属姓名，直系亲属电话不同的次数" }

        String keyName() {'CSIRCDS_INDEX_126'}
    }
    /**
     * 3个月内成功申请的同一直系亲属电话，直系亲属姓名不同的次数
     * 不通过：次数>1
     *    通过：其它情况为通过
     */
    static final Rule CSIRFDS_RULE_5 = new AbstractCSIRFDSBaseRule(){
        String code() { "ZB005" }

        String name() { "3个月内成功申请的同一直系亲属电话，直系亲属姓名不同的次数" }

        String desc() { "3个月内成功申请的同一直系亲属电话，直系亲属姓名不同的次数" }

        String keyName() {'CSIRCDS_INDEX_127'}
    }
    /**
     * 3个月内非亲属紧急联系人+非亲属紧急联系人电话成功申请次数
     * 不通过：次数>1
     *    通过：其它情况为通过
     */
    static final Rule CSIRFDS_RULE_6 = new AbstractCSIRFDSBaseRule(){
        String code() { "ZB006" }

        String name() { "3个月内非亲属紧急联系人+非亲属紧急联系人电话成功申请次数" }

        String desc() { "3个月内非亲属紧急联系人+非亲属紧急联系人电话成功申请次数" }

        String keyName() {'CSIRCDS_INDEX_128'}
    }
    /**
     * 3个月内非亲属紧急联系人电话作为绑定手机号码成功申请的次数
     * 不通过：次数>1
     *    通过：其它情况为通过
     */
    static final Rule CSIRFDS_RULE_7 = new AbstractCSIRFDSBaseRule(){
        String code() { "ZB007" }

        String name() { "3个月内非亲属紧急联系人电话作为绑定手机号码成功申请的次数" }

        String desc() { "3个月内非亲属紧急联系人电话作为绑定手机号码成功申请的次数" }

        String keyName() {'CSIRCDS_INDEX_129'}
    }
    /**
     * 3个月内成功申请的同一非亲属紧急联系人，非亲属紧急联系人电话不同的次数
     * 不通过：次数>1
     *    通过：其它情况为通过
     */
    static final Rule CSIRFDS_RULE_8 = new AbstractCSIRFDSBaseRule(){
        String code() { "ZB008" }

        String name() { "3个月内成功申请的同一非亲属紧急联系人，非亲属紧急联系人电话不同的次数" }

        String desc() { "3个月内成功申请的同一非亲属紧急联系人，非亲属紧急联系人电话不同的次数" }

        String keyName() {'CSIRCDS_INDEX_130'}
    }
    /**
     * 3个月内成功申请的同一非亲属紧急联系人电话，非亲属紧急联系人不同的次数
     * 不通过：次数>1
     *    通过：其它情况为通过
     */
    static final Rule CSIRFDS_RULE_9 = new AbstractCSIRFDSBaseRule(){
        String code() { "ZB009" }

        String name() { "3个月内成功申请的同一非亲属紧急联系人电话，非亲属紧急联系人不同的次数" }

        String desc() { "3个月内成功申请的同一非亲属紧急联系人电话，非亲属紧急联系人不同的次数" }

        String keyName() {'CSIRCDS_INDEX_131'}
    }
    /*********************************************************************************
     * 17. 风险特征规则 - END
     *********************************************************************************/

    static final List<Rule> CSIRFDS_RULE_CHAIN = [
            CSIRFDS_RULE_1,
            CSIRFDS_RULE_2,
            CSIRFDS_RULE_3,
            CSIRFDS_RULE_4,
            CSIRFDS_RULE_5,
            CSIRFDS_RULE_6,
            CSIRFDS_RULE_7,
            CSIRFDS_RULE_8,
            CSIRFDS_RULE_9,
    ]

    /**
     * FINAL RULE
     */
    @Deprecated
    static final Rule FINAL_RULE = new Rule() {
        String code() { "AA999" }

        String name() { "全项校验" }

        String desc() { "全项校验" }

        Rule.Result call(Map paramsMap) {
            Rule.Result.R0000
        }
    }

    /**
     * 融360规则链条
     */
    static final List<Rule> R360_RULE_CHAIN = [
            //融360黑名单规则
            //R360_ORG_B_BLACKLIST_FEEDBACK_RULE,
            //R360_ORG_G_BLACKLIST_FEEDBACK_RULE,
            //R360_BAIDU_BLACKLIST_FEEDBACK_RULE,
            //R360_YIMEI_BLACKLIST_FEEDBACK_RULE,
            //R360_BAIRONG_SPECIAL_BLACKLIST_FEEDBACK_RULE,

            //融360运营商规则
            R360_PHONE_ONLINE_TIME_RULE,
            R360_PHONE_CURRENT_STATUS_RULE,
            R360_PHONE_COST_LEVEL_RULE,

            //融360多头借贷
            R360_LONG_POSITION_CREDIT_RULE,

            //融360个人信贷报告
            R360_INNER_CREDIT_EMERGENCY1_TEL_FEQUENCY_RULE,
            //R360_INNER_CREDIT_COLLECTION_TEL_FEQUENCY_RULE,
            //R360_INNER_CREDIT_LOAN_TEL_FEQUENCY_RULE,
            //R360_INNER_CREDIT_GAMBLE_TEL_FEQUENCY_RULE,
            //R360_INNER_CREDIT_CASHOUT_TEL_FEQUENCY_RULE,
            //R360_INNER_CREDIT_FAKEPAPER_TEL_FEQUENCY_RULE,
            R360_INNER_CREDIT_M3_OVERDUE_RULE,
            R360_INNER_CREDIT_CREDIT_OVERDUE_RULE,
            R360_INNER_CREDIT_FRAUD_RULE,
            R360_INNER_CREDIT_COURT_DISHONEST_RULE,
            R360_INNER_CREDIT_MISCONDUCT_RULE,
            R360_INNER_CREDIT_LOW_CREDIT_RULE,
            R360_INNER_CREDIT_INTERAGENT_RULE,
            //R360_INNER_CREDIT_SEARCH_CNT_LAST_3M_RULE,
            R360_INNER_CREDIT_USER_SOCIAL_SECURITY_RULE,
            R360_INNER_CREDIT_QID77_RULE,
            R360_INNER_CREDIT_TIANJI_SEARCH_RULE,
            //R360_INNER_CREDIT_TIANJI_SEARCH_ORG_RULE,
            R360_INNER_CREDIT_ORDER_ITEM_AMOUNT_RULE,
            R360_INNER_CREDIT_APPROVAL_RATE_RULE,
            R360_INNER_CREDIT_OVERDUE_RATE_RULE,

            //融360银联智测报告
            R360_UNION_PAY_CREDIT_CST_RULE,
            R360_UNION_PAY_CREDIT_COT_RULE,
            R360_UNION_PAY_CREDIT_RSK_RULE,
            R360_UNION_PAY_CREDIT_MTH_12_RULE,
            R360_UNION_PAY_CREDIT_RFM_12_RULE
    ]

    /**
     * 基本模型规则链条
     */
    static final List<Rule> BASE_MODEL_RULE_CHAIN = [
            AGE_RULE,
            IDENTITY_CARD_RULE,
            HIGH_RISK_AREA_RULE
    ]

    /**
     * 人行征信规则链条
     */
    static final List<Rule> CREDIT_REFERENCE_RULE_CHAIN = [
            CREDIT_REFERENCE_EXIST_RULE,
            CREDIT_REFERENCE_LOAN_STATE_RULE,
            CREDIT_REFERENCE_USER_STATE_RULE,
            CREDIT_REFERENCE_LOAN_CARD_STATE_RULE,
            CREDIT_REFERENCE_SEARCH_RECORD_RULE,
            CREDIT_REFERENCE_PERSONAL_INFO_RULE,
            CREDIT_REFERENCE_SCORE_MODEL_RULE
    ]

    static final int CHINA_MAINLAND_IDENTITY_CARD_NUM_LENGTH = 18               //大陆身份证号长度
    static final String HIGH_RISK_ID_PREFIX_TS = "330329"                       //温州泰顺籍
    static final String HIGH_RISK_ID_PREFIX_ND = "3522"                         //宁德籍
    static final Range LEGAL_AGE_RANGE = 18..60                                 //18-60岁
    static final Range BLANK_USER_MODEL_LEGAL_AGE_RANGE = 18..45                //18-45岁
    static final int MAX_CREDIT_REFERENCE_SEARCH_RECORD_NUM_INCLUSIVE = 6       //征信最大查询次数
    static final int MAX_CREDIT_REFERENCE_SEARCH_RECORD_DURATION_INCLUSIVE = 90 //征信最大查询时间
    static final String FULL_DATE_FORMAT_1 = 'yyyy-MM-dd HH:mm:ss'
    static final String FULL_DATE_FORMAT_2 = 'yyyy.MM.dd HH:mm:ss'
    static final String YEAR_MONTH_DATE_FORMAT = 'yyyy.MM'
    static final String SIMPLE_DATE_FORMAT = 'yyyy.MM.dd'
    static final String IS_LIST_MISS = '0'
    static final String IS_LIST_HINT = '1'
    static final String IS_LIST_KEY = 'isList'

    static final long DEFAULT_CASH_QUOTA = 0L
    static final long BLANK_USER_CONSUMPTION_MIN_QUOTA = 200L
    static final long BLANK_USER_CONSUMPTION_MAX_QUOTA = 2000L
    static final long NON_BLANK_USER_CONSUMPTION_MIN_QUOTA = 1000L
    static final long NON_BLANK_USER_CONSUMPTION_MAX_QUOTA = 30000L
    static final String R360_RESPONSE_OK = '200'
    static final String R360_RESPONSE_CODE_KEY = 'code'
    static final String R360_RESPONSE_CONTENT_KEY = 'content'

    static final double CREDIT_REFERENCE_DEFAULT_SCORE = 520.85

    /**
     * 首张信用卡发卡到现在的月数评分卡
     */
    static final ScoreCards SCORE_CARD__FIRST_CREDIT = [
            5.48,
            [1,22,-15.62],
            [23,27,-13.05],
            [28,34,-6.10],
            [35,42,-1.06],
            [43,55,4.17],
            [56,77,13.66],
            [78,103,20.01],
            [104,99999,32.23],
    ]

    /**
     * 贷记卡账户数评分卡
     */
    static final ScoreCards SCORE_CARD__CREDIT_COUNT = [
            34.92,
            [0,2,-6.85],
            [3,4,-0.12],
            [5,5,3.35],
            [6,12,5.59],
            [13,99999,11.35]
    ]

    /**
     * 信用卡余额占授信额比例评分卡
     */
    static final ScoreCards SCORE_CARD__CREDIT_REMAINDER = [
            4.39,
            [0,0,-13.15],
            [1,1,-6.79],
            [2,2,3.13],
            [3,3,7.32],
            [4,4,12.54],
            [5,5,14.01],
            [7,99999,12.63]
    ]

    /**
     * 住房贷款总额评分卡
     */
    static final ScoreCards SCORE_CARD__HOUSE_LOAN = [
            -1.00,
            [1,280000, 4.30],
            [280001,999999999, 14.26]
    ]

    /**
     * 未结清且没有逾期记录的贷款的最大账龄评分卡
     */
    static final ScoreCards SCORE_CARD__MAX_LOAN_DEBT_DATE = [
            2.82,
            [0,13, 2.82],
            [14,15, -22.75],
            [16,18, -14.81],
            [19,24, -4.33],
            [25,39, 4.74],
            [40,99999, 13.99]
    ]

    /**
     * 未结清贷款最高剩余还款期数评分卡
     */
    static final ScoreCards SCORE_CARD__LOAN_DEBT_PERIODS = [
            1.30,
            [0,111, -7.37],
            [112,229, 11.55],
            [230,99999, 23.27],
    ]

    /**
     * 未结清贷款，本月应还款之和
     */
    static final ScoreCards SCORE_CARD__SUM_OF_LOAN_MONTH_DEBT = [
            0.83,
            [0,200, -8.72],
            [201,700, -3.54],
            [701,1400, 0.74],
            [1401,3700, 5.94],
            [3700,99999999, 8.42]
    ]

    static final double EXISTING_USER_MODEL_DEFAULT_SCORE = 0.0D

    /**
     * 1.注册时间距当前时间月份数
     */
    static final ScoreCards SCORE_CARD__REGISTRY_MONTH_DURATION = [
            42.0,
            [10,17, 41.0],
            [18,25, 46.0],
            [26,36, 50.0],
            [37,9999, 51.0]
    ]

    /**
     * 3.近12个月的访问次数评分卡
     */
    static final ScoreCards SCORE_CARD__VISIT_COUNT_IN_12M = [
            41.0,
            [0,51, 42.0],
            [52,102, 43.0],
            [103,179, 45.0],
            [180,326, 47.0],
            [327,99999999, 50.0]
    ]

    /**
     * 4.过去30天基金日均持有份额评分卡
     */
    static final ScoreCards SCORE_CARD__FND_AVG_HOLD_AMT_1M = [
            45.0,
            [0,68, 29.0],
            [69,391, 47.0],
            [392,1879, 58.0],
            [1880,7530, 91.0],
            [7531,99999999, 97.0]
    ]

    /**
     * 5.近一个月易付宝绑定银行卡数量
     */
    static final ScoreCards SCORE_CARD__BIND_NUM_1M = [
            22.0,
            [0,0, 52.0],
            [1,9999, 41.0]
    ]

    /**
     * 6.线上整体活跃度标签评分卡
     */
    static final ScoreCards SCORE_CARD__ACTV_RFR_ON = [
            34.0,
            [0,0, -2.0],
            [1,1, 53.0],
            [2,2, 29.0],
            [3,3, 41.0],
            [4,4, 49.0],
            [5,5, 43.0],
            [6,6, 41.0],
            [7,7, 35.0]
    ]

    /**
     * 7.近一个月线上平均购买金额评分卡
     */
    static final ScoreCards SCORE_CARD__BUY_AMT_ONLINE_1M = [
            44.0,
            [0,76, 52.0],
            [77,167, 49.0],
            [168,1180, 44.0],
            [1181,99999999, 37.0]
    ]

    /**
     * 8.过去60天申购的基金金额总和评分卡
     */
    static final ScoreCards SCORE_CARD__FND_PRCH_CNT_2M = [
            45.0,
            [0,230, 46.0],
            [231,3259, 35.0],
            [3260,34899, 47.0],
            [34900,99999999, 71.0]
    ]

    /**
     * 9.近一个月是否购买过3C
     */
    static final ScoreCards SCORE_CARD__IS_3C_BUYER_1M = [
            37.0,
            [0,0, 48.0],
            [1,1, 40.0]
    ]

    /**
     * 10.是否大家电买家群
     */
    static final ScoreCards SCORE_CARD__IS_DJD_BUYER = [
            34.0,
            [0,0, 41.0],
            [1,1, 48.0]
    ]

    /**
     * 11.生命周期
     */
    static final ScoreCards SCORE_CARD__LIFE_CYCLE = [
            56.0,
            [2,2, 29.0],
            [3,3, 41.0],
            [4,4, 19.0],
            [5,5, 32.0],
            [6,6, 8.0],
            [7,7, 41.0],
            [8,8, 29.0],
            [9,9, 26.0],
            [10,10, 8.0],
    ]

    /**
     * 12.忠诚度
     */
    static final ScoreCards SCORE_CARD__LOYALTY_LEVEL = [
            73.0,
            [0,0, 52.0],
            [1,1, 54.0],
            [2,2, 49.0],
            [3,3, 39.0],
            [4,4, 32.0],
            [5,5, 22.0]
    ]

    /**
     * 13.最早的基金申请时间距离当前时间距离当前时间的月数
     */
    static final ScoreCards SCORE_CARD__FIRST_FND_DT = [
            45.0,
            [0,5, 42.0],
            [6,11, 47.0],
            [12,15, 49.0],
            [16,99999, 54.0]
    ]

    /**
     * 14.近12个月内不同的订单不同的送货地址数目
     */
    static final ScoreCards SCORE_CARD__ORD_ADDR_CNT_12M = [
            56.0,
            [0,1, 48.0],
            [2,2, 45.0],
            [3,3, 44.0],
            [4,6, 42.0],
            [7,99999, 39.0]
    ]

    static final class ScoreCards{
        List<ScoreCard> scoreCards = []
        double defaultScore

        ScoreCards(double defaultScore, List... scoreCardParamsLists){
            this.defaultScore = defaultScore
            this.scoreCards += scoreCardParamsLists.collect { scoreCardParams -> scoreCardParams as ScoreCard}
        }

        double getScore(long val){
            ScoreCard scoreCard = scoreCards.find {
                scoreCard -> scoreCard.isInRange(val)
            }
            scoreCard ? scoreCard.score : getDefaultScore()
        }

        double getDefaultScore(){
            defaultScore
        }
    }

    static final class ScoreCard {
        long from
        long to
        double score

        ScoreCard(long from, long to, double score) {
            this.from = from
            this.to = to
            this.score = score
        }

        boolean isInRange(long val){
            val >= from && val <= to
        }
    }

    static enum ReturnResult{
        APPROVED('0', '0000', '通过'),
        REJECTED('1', '0000', '拒绝'),
        MANUALLY('2', '0000', '转人工')

        String admit
        String value
        String desc

        ReturnResult(String admit, String value, String desc) {
            this.admit = admit
            this.value = value
            this.desc = desc
        }
    }

    static interface Rule {

        enum Result {
            R0000   ('0000',   '通过-返回通过'),
            R0001   ('0001',   '算法异常-参数校验失败'),
            R0002   ('0002',   '算法异常-算法文件错误，不包含sql'),
            R0003   ('0003',   '算法异常-算法实例为空/算法文件错误,分片参数不完整'),
            R0005   ('0005',   '算法异常-算法文件错误,暂不支持sub以外的算法'),
            R9991   ('9991',   '客户类型不存在-客户类型不存在'),
            R9999   ('9999',   '异常-返回异常'),
            R00001  ('00001',  '转人工'),
            R021002 ('021002', '基本准入规则不符合-客户年龄是否在准入年龄范围内'),
            R021001 ('021001', '基本准入规则不符合-贷款人所属户籍验证'),
            R021004 ('021004', '基本准入规则不符合-易付宝会员激活时间是否满足'),
            R021003 ('021003', '基本准入规则不符合-贷款人是否属于风险高危地区'),
            R010004 ('010004', '反欺诈校验不通过-触发黑名单-借款人是否在CSI黑名单'),
            R010003 ('010003', '反欺诈校验不通过-触发黑名单-借款人是否在任性付黑名单'),
            R010007 ('010007', '反欺诈校验不通过-触发黑名单-借款人是否在融360黑名单'),
            R010005 ('010005', '反欺诈校验不通过-触发黑名单-借款人是否在易购风控黄牛名单库'),
            R010006 ('010006', '反欺诈校验不通过-会员状态是否资金冻结'),
            R010001 ('010001', '反欺诈校验不通过-会员状态是否会员冻结'),
            R010002 ('010002', '反欺诈校验不通过-会员状态是否不收不付冻结'),
            R023001 ('023001', '存量用户评分模型不符合-存量用户评分为***，分数过低'),
            R023002 ('023002', '存量用户评分模型不符合-存量用户评分为***，属于中间值'),
            R023003 ('023003', '不满足存量用户评分模型计算条件'),
            R024000 ('024000', '白板用户通过转人工审核-白板用户通过转人工审核'),
            R024001 ('024001', '白板用户模型不符合-白板用户借款人婚姻状态及年龄不符合'),
            R024002 ('024002', '白板用户模型不符合-白板用户工作状态不符合'),
            R024003 ('024003', '白板用户模型不符合-白板用户收入负债比不符合'),
            R024004 ('024004', '白板用户模型不符合-白板用户学历不符合'),
            R024005 ('024005', '白板用户模型不符合-白板用户在网时长和在网状态不符合'),
            R024006 ('024006', '白板用户年龄验证不符合'),
            R022001 ('022001', '人行评分模型不符合-最近12个月内有3次以上逾期'),
            R022002 ('022002', '人行评分模型得分<=561分'/*'人行评分模型不符合-人行评分为***，属于中中间值'*/),
            R022003 ('022003', '人行评分模型得分<=530分'/*'人行评分模型不符合-人行评分为***，分数过低'*/),
            R022004 ('022004', '人行评分模型不符合-人行评分为***，但逾期金额大于300'),
            R022005 ('022005', '人行评分模型不符合-贷款信息中包含"次级"、"可疑"、"损失" "冻结"、"止付" "呆账" 等情况'),
            R022006 ('022006', '人行评分模型不符合-担保信息中包含"次级"、"可疑"、"损失" 等情况'),
            R022007 ('022007', '人行评分模型不符合-贷记卡信息中包含 "冻结"、"止付" "呆账" 等情况'),
            R022008 ('022008', '信贷审批查询记录明细中近三个月内人行征信报告查询次数6次及以上，查询原因为信用卡审批或贷款审批'),
            R022009 ('022009', '人行评分模型不符合-征信资产处置信息不符合'),
            R022010 ('022010', '人行评分模型不符合-保证人代偿信息不符合'),
            R022011 ('022011', '人行评分模型不符合-查询当前逾期'),
            R022012 ('022012', '五级分类包含“关注”、“次级”、“可疑”、“损失”情况（含历史记录存在关注、次级、可疑、损失）'),
            R022013 ('022013', '账户状态验证不符合-账户状态中包含“担保人代偿”、“以资抵债”、“冻结”、“止付”'),
            R022014 ('022014', '贷记卡、准贷记卡、贷款当前状态验证不符合'),
            R022015 ('022015', '人行征信报告查询次数不符合-三个月内查询6次及以上，查询原因为信用卡审批或贷款审批的'),
            R022016 ('022016', '申请用户姓名、身份证号、手机号码与征信报告中信息不一致'),
            R022017 ('022017', '无人行征信报告'),
            R022018 ('022018', '不满足人行评分模型计算条件'),
            R022000 ('022000', '人行评分通过转人工审核-人行评分为***，通过'),
            R025001 ('025001', '鹏元征信规则不符合-鹏元征信客户手机号码不存在'),
            R025002 ('025002', '鹏元征信规则不符合-鹏元征信申请用户与手机号码登记身份证号码不一致'),
            R025003 ('025003', '鹏元征信规则不符合-鹏元征信申请用户与手机号码登记姓名不一致'),
            R025004 ('025004', '鹏元征信规则不符合-鹏元征信系统无数据'),
            R025005 ('025005', '鹏元征信规则不符合-鹏元征信查询超时'),
            R025006 ('025006', '鹏元征信规则不符合-鹏元征信查询数据异常'),
            R025007 ('025007', '鹏元征信规则不符合-鹏元三要素校验不通过且“是否有人行征信报告”校验也不通过'),
            R025008 ('025008', '鹏元征信规则不符合-鹏元三要素校验不通过且“申请用户姓名、身份证号、手机号码与征信报告中信息是否一致”校验也不通过'),
            R025000 ('025000', '鹏元征信规则通过-鹏元征信鹏元征信规则通过'),
            R026001 ('026001', '融360黑名单-借款人是否在融360机构B黑名单'),
            R026002 ('026002', '融360黑名单-借款人是否在融360机构G黑名单'),
            R026003 ('026003', '融360黑名单-借款人是否在融360查询百度征信黑名单'),
            R026004 ('026004', '融360黑名单-借款人是否在融360查询亿美黑名单'),
            R026005 ('026005', '融360黑名单-借款人是否在融360百融特殊名单'),
            R026101 ('026101', '融360多头贷款-查询多次申请记录'),
            R026201 ('026201', '融360运营商-手机号码当前状态校验不通过'),
            R026202 ('026202', '融360运营商-手机号码在网时长信息校验不通过'),
            R026203 ('026203', '融360运营商-手机号码消费档次校验不通过'),
            //融360个人信贷报告
                    R026301 ('026301', '与紧急联系人1月均电话次数过低'),
            R026302 ('026302', '与催收类号码月均电话次数验证'),
            R026303 ('026303', '与贷款类号码月均电话次数验证'),
            R026304 ('026304', '与赌博类号码月均电话次数验证'),
            R026305 ('026305', '与套现类号码月均电话次数验证'),
            R026306 ('026306', '与假证类号码月均电话次数验证'),
            R026401 ('026401', '命中M3以上借贷逾期的客户名单'),
            R026402 ('026402', '命中信用卡逾期记录的名单'),
            R026403 ('026403', '命中借贷过程中被核实欺诈的客户名单'),
            R026404 ('026404', '命中法律失信相关记录的名单'),
            R026405 ('026405', '命中具有赌博、吸毒等不良记录的名单'),
            R026406 ('026406', '命中信用记录偏低的用户名单'),
            R026407 ('026407', '命中贷款中介的名单'),
            //融360个人信贷报告
                    R026501 ('026501', '资质记录-用户借贷申请中的资质记录：近3个月搜索次数超过限额'),
            R026502 ('026502', '资质记录-用户借贷申请中的资质记录：低本地社保的置信度'),
            R026503 ('026503', '资质记录-用户借贷申请中的资质记录：低公积金的置信度'),
            R026504 ('026504', '贷款记录-用户多次借款申请记录-天机总体：近2个月查询次数超过限额'),
            R026505 ('026505', '贷款记录-用户多次借款申请记录-天机总体：近3个月查询机构数超过限额'),
            R026506 ('026506', '贷款记录-用户多次借款申请记录-融360下单明细：融360下单明细-信用贷总量超过限额'),
            R026507 ('026507', '审批记录-用户借贷审批结果记录：总体信息-审批成功率不通过'),
            R026508 ('026508', '审批记录-用户借贷审批结果记录：总体信息-逾期率不通过'),
            //融360银联智测报告-个人银联检查
                    R026601 ('026601', '卡状态得分校验不通过'),
            R026602 ('026602', '套现模型得分校验不通过'),
            R026603 ('026603', '风险得分不通过'),
            R026604 ('026604', '近12个月发生交易月份数过低'),
            R026605 ('026605', '近12个月笔均交易金额过低')

            String code
            String desc

            Result(String code, String desc) {
                this.code = code
                this.desc = desc
            }

            boolean isSuccess() { this == R0000 }

            boolean isFailed() { !isSuccess() }
        }

        String code()

        String name()

        String desc()

        Rule.Result call(Map paramsMap)
    }
}
