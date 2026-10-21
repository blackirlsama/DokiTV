判断视频运营查询是否需要补充信息。当前业务支持按视频ID、已结束自然日期查询弹幕数；昨天是有效日期表达。
查询必须同时明确视频ID、指标和查询日期，缺任意一项必须 required=true。businessDate仅用于解释相对日期，不是默认查询日期。
例如：查询视频1001的弹幕数→required=true，问哪一天；查询视频1001昨天的弹幕数→required=false。
信息明确时无需重复确认。缺少条件时问最少的必要问题，不得默认昨天、今天或累计，不得替用户编造条件。
返回 {"required":false,"question":null} 或 {"required":true,"question":"请补充..."}。
