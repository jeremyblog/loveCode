package controllers;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.kingdee.json.*;
import com.kingdee.rabbitmq.MQSendMsg;
import com.kingdee.redis.JedisClient;
import com.kingdee.util.MD5Util;
import com.kingdee.util.RSAUtils;

import constant.PubAttrConstant;
import exception.HttpException;
import exception.JSONP;
import exception.JSONPException;
import exception.PubException;
import exception.ResultSign;
import job.*;
import models.*;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;

import play.*;
import play.cache.Cache;
import play.libs.Time;
import play.libs.WS;
import play.libs.WS.HttpResponse;
import play.libs.WS.WSRequest;
import play.mvc.*;
import play.mvc.Http.Header;
import play.mvc.results.Redirect;
import redis.clients.jedis.Jedis;
import service.AbstractPubRecMsgService;
import service.MonitorService;
import service.MsgService;
import service.NetWorkAdmin2PubAdminAtCreatePubService;
import service.OpenAuth2Service;
import service.PubOperateService;
import service.PubRecMsgService;
import service.SendMsgService;
import utils.DateUtil;
import utils.FileCacheUtils;
import utils.HttpClientUtil;
import utils.ValidateUtil;

import java.io.File;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * @author z
 */
public class PublicAPI extends Controller {

    public static final String FB = "公共号接口调用出错上千次,已经被封ip=";
    static ValidateUtil.ValidateEmpty ValidateEmpty2Info = new ValidateUtil.ValidateEmpty2Info();
    static PubAccountBO bo = PubAccountBO.getInstance();
    static XuntongBO xt = new XuntongBO();
    static OpenBO open = new OpenBO();
    static AppSubService appSubService = new AppSubService();
    static MsgService msgService = new MsgService();
    static ManageBO manage = new ManageBO();
    static ReportBO reportBO = new ReportBO();
    static ToDoMsgBO toDoMsgBO = new ToDoMsgBO();
    
    static LockBO lockBO = new LockBO();
    
    static org.apache.log4j.Logger errlog = org.apache.log4j.Logger.getLogger("error");
    static PubAuthService pubAuthService = new PubAuthService(); // 公共号授权管理员发言人
    
    static String gw = Play.configuration.getProperty("gw", "http://183.62.224.85:8180/vgateway");
    
    static String opencodeUrl = Play.configuration.getProperty("opencodeUrl", "http://192.168.22.142");
    
    static String imgeOutUrl = Play.configuration.getProperty("imgeOutUrl", "http://192.168.22.142");

    static OpenAuth2Service openAuth2Service = OpenAuth2Service.getInstance();
    
    static MonitorService monitorService = MonitorService.getInstance();
    
    public static String openapi = Play.configuration.getProperty("openapi");
    
    public static String ticketurl = Play.configuration.getProperty("ticket");
	// 验证码接口业务类型
	public static final String CODETYPE = "pubweb";
	// 验证码接口业务关键字
	public static final String CODEKEY = UUID.randomUUID().toString().replaceAll("\\-", "");
	
	static AbstractPubRecMsgService pubRecMsgService = new PubRecMsgService();
    /**
     * 公众号发消息给讯通
     * MID --> pubsend --> sendxt  --> sendbymid -->  PubSendJob  --> sendMessage --> XT
     * MID --> pubsend/msgsend --> PUB  --> PubReplyJob  --> sendMessage --> XT
     * GW   --> gwsend
     * MID <-- PubPushJob      <--  PubReplyJob
     */
    public static void pubsend() {
    	try {
    		params.getRootParamNode();
        	String body = params.data.get("body")[0];
        	JSONObject result = msgService.sendMsgByApi(body);
        	session.put("pid", result.getString("pubId"));
        	renderJSON(result);
		} catch (PubException e) {
			String detail = e.getDetail();
			if (StringUtils.isEmpty(detail)) {
				errorText(e.getStatusCode(), e.getMessage());
			} else {
				errorText(e.getStatusCode(), e.getMessage(), e.getDetail());
			}
		} catch (Exception e) {
			Logger.error(e.getMessage());
			errorText(500, e.getMessage());
		}
    }

    /**
     * 公众号发送网关消息到微信
     * MID --> msgsend --> sendwx  --> PubPushJob  -->GW
     */

    public static void msgsend() throws Exception {

        String sfrom = "";

        Gson gs = new Gson();

        MessageFrom sendfrom;
        MessageVO msgs;

        params.getRootParamNode();
        String body = params.data.get("body")[0];
        // Logger.info("pubsend body SIZE %d ", );
        if (body.length() > 1000000) {
            errorText(5002, "传入数据长度超过了1M,请裁剪!");
            return;
        }
        try {
            msgs = gs.fromJson(body, MessageVO.class);
        } catch (JsonSyntaxException e) {
            errorText(5000, "没有传入规定的参数,或是错误JSON格式", e.getMessage());
            return;

        }

        sendfrom = msgs.from;
        sfrom = gs.toJson(sendfrom);

        Logger.info("msgsend %s ", sfrom);

        // 验证公司签名
        if (sendfrom == null || sendfrom.no == null || sendfrom.pub == null
                || sendfrom.time == null || sendfrom.pubtoken == null || sendfrom.nonce == null) {
            errorText(5000, "必须传入参数:from{no,pub,time,nonce,pubtoken}");
        }

        String from = sendfrom.pub;

        if (Cache.get("pubsend:" + sfrom) != null) {
            errorText(5005, "不要频发公共号,请等分钟!", from);
        }

        PubUtils.debug(from, body);

        PubPO pub = bo.findPub(from);
        if (pub == null || pub.state != 2) {

            errorText(5001, "公共号不存在或未审核！", from);
        }
        //公共号密钥验证
        if (StringUtils.isEmpty(pub.pubkey))
            errorText(5004, "公共号未设置密钥！", from);

        String ptoken = MD5Util.sha(pub.pubkey, sendfrom.no, sendfrom.pub, sendfrom.time, sendfrom.nonce);
        if (!ptoken.equalsIgnoreCase(sendfrom.pubtoken)) {
            errorText(5004, "公共号密钥验证失败！", "pub %s token %s sha %s", sendfrom.pub, sendfrom.pubtoken, ptoken);
        }

        String msgid = sendwx(msgs, pub);

        //保存发送
        bo.sndMsg(from, msgid);
        Cache.set("pubsend:" + sfrom, "1", "1min");
        bo.hincrBy(PubAccountBO.PUBCACHE, "api_msgcnt", 1);

        ok();
    }

    /**
     * 发送到微信*
     */
    private static String sendwx(MessageVO msgs, PubPO pub) throws Exception {
        if (msgs.gwmsg == null || msgs.gwmsg.Payload == null)
            errorText(5000, "微信消息必须传入参数:gwmsg");

        String serverurl = gw + "/messageoutput";

        GwHead gwh = msgs.gwmsg;
        gwh.PubAccId = pub.pid;
        gwh.ServiceType = GwHead.WX;

        //推送接口
        new PubPushGWJob(serverurl, gwh).doJob();

        return "微信";
    }

    /**
     * 发送到公共号的html
     *
     * @throws Exception
     */
    public static void pubhtml() throws Exception {

        String from = params.get("pub"),
                no = params.get("no"),
                html = params.get("html"),
                date = params.get("date"),
                token = params.get("token");

        Logger.info("pubhtml pub %s no %s ,date %s token %s", from, no, date, token);

        // 验证公司签名
        if (from == null || no == null || html == null
                || date == null || token == null) {
            errorText(501, "必须传入参数:pub,no,date,token,html");
        }

        //PubAcctPO pub = McloudBO.getPub(from);
        if (Play.mode == Play.Mode.PROD) {

            if (!McloudBO.encryptOk(no, date, token)) {
                errorText(503, "公司3GNO签名无效!");
            }
        }

        renderText(PubUtils.saveHtml(from, html, false));

    }

    /**
     * 公共号订阅
     *
     * @throws Exception
     */
    public static void pubssb(String pubid, String mid, String userid, String sayid,
                              String ssb, String time, String pubtoken) throws Exception {
    	
    	Logger.info("pubssb %s,%s", pubid, params.allSimple().toString());
    	
    	if (pubid == null || mid == null) {
            errorText(5000, "必须传入参数(pubid,mid),可选(time,pubtoken,ssb)", pubid);
        }
    	
        PubPO vo = bo.findPub(pubid);

        if (vo == null) { //|| !bo.isRunPub(vo.pid) 公共号所用者,不需判断订阅
            errorText(5001, "公共号不可订阅", pubid);
        }
        
        if (StringUtils.isEmpty(vo.pid)) {
			Logger.error("公共号[%s]不存在!", pubid);
			errorText(5001, String.format("公共号[%s]不存在!", pubid), pubid);
		}
        
        //查询订阅: 1=公司默认订阅 or 选择订阅
        if (StringUtils.isEmpty(ssb)) {
            JSONObject jo = new JSONObject("ssb", vo.mssb ? "1" : (bo.isssbPub(mid, pubid) ? "1" : "0"));
            renderJSON(jo);
        }

        //公共号密钥验证
        if (StringUtils.isNotEmpty(vo.pubkey)) {
            //errorText(5004, "公共号未设置密钥！");
            if (time == null || pubtoken == null) {
                errorText(5004, "公共号密钥验证必须传入参数(pubid,mid,time,pubtoken)", pubid);
            }

            String ptoken = MD5Util.sha(vo.pubkey, pubid, mid, time);
            if (!ptoken.equalsIgnoreCase(pubtoken)) {
                errorText(5004, "公共号密钥验证失败！", "pub %s token %s#%s", pubid, pubtoken, ptoken);
            }
        }
        
        if (StringUtils.isNotEmpty(userid)) {
            List<String> us = open.getpersonByopenids(PubUtils.toList(userid));
            if (us == null || us.size() < 1) {
                //errorText(5001, "无法找到对应用户", pubid);
            	Logger.error("无法找到对应用户！");
                us = new ArrayList<String>();
                us.add(userid);
            }

            String uid = us.get(0);
            if (ssb.equals("1")) {
            	long lock = lockBO.lock(lockBO.expire, lockBO.ADMIN_GRANT_LOCK, vo.pid, uid);
            	if (1 == lock) {
            		appSubService.subApp(vo.pid, us);
                    if (!bo.alreadyTip(bo.SSBREPLY_USERS, pubid, mid, uid)) {
                    	Logger.info(bo.genKey(bo.SSBREPLY_USERS, pubid, mid, uid) + "开始应答...");
                    	bo.setTips(bo.SSBREPLY_USERS, pubid, mid, uid);
                    	dossbReply(pubid, us.get(0), vo.name);
                    	if (PubAttrConstant.ADMIN_ASSISTANT_ID.equals(pubid)) { // 当是管理员助手公共号时
    						bo.grantAdmin(mid, uid);
    					}
    				} else {
    					Logger.info("已经应答过!!!");
    				}
                    lockBO.unlock(lockBO.ADMIN_GRANT_LOCK, vo.pid, uid);
                    Logger.info("当前已经存在管理员身份授权的锁已解开... %s, %s", vo.pid, uid);
				} else {
					Logger.info("当前已经存在管理员身份授权锁... %s, %s", vo.pid, uid);
				}
                //if (pubid.equals("XT-f8810fb5-78a0-4478-acd3-a2610dabe60d"))
                // xt.sendTextMsg(vo.pid, us.get(0), "你已被设置为工作圈[注册号:"+mid+"]管理员!", "");
            } else {
            	long lock = lockBO.lock(lockBO.expire, lockBO.ADMIN_REVOKE_LOCK, vo.pid, uid);
            	if (1 == lock) {
            		if (pubid.equals(PubAttrConstant.ADMIN_ASSISTANT_ID)) {
                    	if (bo.isNetworkAdmin(mid, uid)) {
                    		bo.revokeAdmin(mid, uid);
                    		xt.sendTextMsg(vo.pid, uid, "你的工作圈管理员身份已被撤销!", "");
    					} else {
    						Logger.info("已经被撤销工作圈管理员或者不是工作圈管理员!!!");
    					}
                    } 
                    appSubService.unSubApp(vo.pid, us);
                    lockBO.unlock(lockBO.ADMIN_REVOKE_LOCK, vo.pid, uid);
                    Logger.info("当前已经存在管理员身份撤销的锁已解开... %s, %s", vo.pid, uid);
				} else {
					Logger.info("当前已经存在管理员身份撤销锁... %s, %s", vo.pid, uid);
				}
            	
            }
            renderJSON(new Result2(true));
        }
        
        
        if (StringUtils.isNotEmpty(sayid)) {
            List<String> us = open.getpersonByopenids(PubUtils.toList(sayid));
            if (us == null || us.size() == 0) {
                //errorText(5001, "无法找到对应发言人", pubid);
                us = new ArrayList<String>();
                us.add(sayid);
            }
            if (ssb.equals("1")) {
            	long lock = lockBO.lock(lockBO.expire, lockBO.ASS_SAY_GRANT_LOCK, vo.pid, sayid);
            	if (1 == lock) {
            		if (!bo.isGroupUser(pubid, Sess.ASS_SAY, sayid)) {
            			bo.addGroupUser(vo.pid, Sess.ASS_SAY, new String[]{us.get(0)});
            			bo.addAssUser(sayid, pubid);
            			xt.sendTextMsg(pubid, sayid, "你已设置为发言人!", "");
            		} else {
            			Logger.info("已经设置发言人过!!!");
            		}
            		lockBO.unlock(lockBO.ASS_SAY_GRANT_LOCK, vo.pid, sayid);
            		Logger.info("当前已经存在发言人身份授权的锁已解开... %s, %s", vo.pid, sayid);
				} else {
					Logger.info("当前已经存在发言人身份授权锁... %s, %s", vo.pid, sayid);
				}
            	
            } else {
            	
            	long lock = lockBO.lock(lockBO.expire, lockBO.ASS_SAY_REVOKE_LOCK, vo.pid, sayid);
            	if (1 == lock) {
            		if (bo.isGroupUser(pubid, Sess.ASS_SAY, sayid)) {
            			bo.delGroupUser(vo.pid, Sess.ASS_SAY, new String[]{us.get(0)});
            			bo.delAssUser(sayid, pubid);
            			xt.sendTextMsg(pubid, sayid, "你为发言人已被撤销!", "");
            		} else {
            			Logger.info("已经撤销发言人过!!!");
            		}
            		lockBO.unlock(lockBO.ASS_SAY_REVOKE_LOCK, vo.pid, sayid);
            		Logger.info("当前已经存在发言人身份撤销的锁已解开... %s, %s", vo.pid, sayid);
				} else {
					Logger.info("当前已经存在发言人身份撤销锁... %s, %s", vo.pid, sayid);
				}
            }
            renderJSON(new Result2(true));
        }

        if (ssb.equals("1")) {
            bo.ssbPub(mid, vo.pid);
            appSubService.subApp(vo.pid, mid);
        } else {
            bo.unssbPub(mid, vo.pid);
            appSubService.unSubApp(vo.pid, mid);
        }

        renderJSON(new Result2(true));
        
    }

    /**
     * 公共号订阅
     *
     * @throws Exception
     */
    public static void pubssbusers(String pubid, int page, int count, String time, String pubtoken) throws Exception {
        Logger.info("pubssbusers %s", pubid);

        if (pubid == null || time == null || pubtoken == null) {
            errorText(5000, "必须传入参数(pubid,time,pubtoken,page,count)");
        }
        PubPO vo = bo.findPub(pubid);

        if (vo == null) {
            errorText(5001, "无效公共号", pubid);
        }


        //公共号密钥验证
        if (StringUtils.isEmpty(vo.pubkey))
            errorText(5004, "公共号未设置密钥！");

        String ptoken = MD5Util.sha(vo.pubkey, pubid, time);
        if (!ptoken.equalsIgnoreCase(pubtoken)) {
            PubUtils.debug(pubid, "sha %s=%s", pubid, ptoken);
            errorText(5004, "公共号密钥验证失败！", "pub %s token %s#%s", pubid, pubtoken, ptoken);
        }
        if (count < 10) count = 10;

//      JSONArray users = xt.subusers(pubid, null, page, count);
        JSONArray users = bo.searchPubUsers(pubid, "", "", page * count, count);
        if (null == users || users.isEmpty()) {
        	String message = "该公共号暂无订阅用户!";
        	Logger.warn(message);
			renderJSON(new Result2(message));
		}
        users = open.getpersonsbyids(users.strList("id"));
        if (null == users) {
        	String message = "服务器异常!请稍候再试!";
        	Logger.warn(message);
			renderJSON(new Result2(message));
		}
        renderJSON(users.toJSONString());

    }

    /**
     * 创建公共号
     * 
     * @since auto 特性加于2015-06-25 李杨晶的需求 KSSP-20897
     * @param remind
     * @param auto 默认为空
     * 
     * 2015-08-13
     * 创建公共号的企业管理员自动成为公共号管理员, 
     * 对外创建公共号时默认为【创建公共号的企业管理员不会自动成为公共号管理员】
     * 在公共号平台创建公共号时默认为【创建公共号的企业管理员自动成为公共号管理员】
     * 
     * 
     * @throws Exception
     */
    public static void pubcreate(boolean remind, Boolean auto) throws Exception {

        PubPO po = new PubPO();
        po.mid = params.get("mid");
        po.name = params.get("name");
        po.remind = remind;
        po.auto = auto;
        
        String token = params.get("sign");
        if (po.mid == null || po.name == null) {
            errorText(500, "必须传入参数:name,mid");
        }
        if (Play.mode == Play.Mode.PROD) {
            if (!McloudBO.encryptOk(po.mid,po.name,token)) {
                Logger.error("公司签名无效:mid=%s,name=%s,sign=%s",po.mid,po.name,token);
                errorText(503, "公司签名无效!");
            }
        }

        List<PubPO> pubs = bo.findMidPub(po.mid);
        for (PubPO p : pubs) {
            if (p.name.equals(po.name)) {
                po = p;
                break;
            }
        }
        if (po.state == 0) {
            Logger.info("pubcreate name=%s,mid=%s", po.name, po.mid);
            po.midname = params.get("midname");
            po.photourl = params.get("photourl");
            po.pubkey= params.get("pubkey");

            po.type = 2;
            po.pid = "XT-" + UUID.randomUUID();
            po.time = System.currentTimeMillis();
            po.state = 2;
            po.states = "启用";

            String cssb = params.get("cssb");
            String allssb = params.get("allssb");
            String reply = params.get("reply");


            if (StringUtils.equals(cssb, "1")) {
                po.cssb = true;
            }
            if (StringUtils.equals(allssb, "1")) {
                po.allssb = true;
            }
            if (StringUtils.equals(reply, "1")) {
                po.reply = true;
            }
            if (StringUtils.equals(params.get("app"), "1")) {
                po.app = "1";
            }

            if (po.midname == null) {
                po.midname = "企业" + po.mid;
            }
        }

        if (StringUtils.isNotEmpty(params.get("photoname")) && StringUtils.isNotEmpty(params.get("photo64"))) {
            po.photourl = PubUtils.saveFile("icon", params.get("photoname"), params.get("photo64"));
        }
        
        bo.savePubAuto(po);

        if (po.allssb) {
            appSubService.subApp(po.pid, po.mid);
        }

        // 2015-08-13屏蔽, 原因对外的这个时期该接口是不能获取到当前公共号创建人的, 所以无法指定公共号创建人自动成为公共号管理员
        // NetWorkAdmin2PubAdminAtCreatePubService.carryout(po); 
        renderText(po.pid);

    }
    
    /**
     * 给mcloud自动创建公共号通道
     * @param remind
     * @param auto
     * @throws Exception
     */
    public static void pubautoCreate() throws Exception {
    	 PubPO po = new PubPO();
    	 JSONObject data = new JSONObject();
         po.appid = params.get("appid");
         po.name =  params.get("name");
         po.remind = false;
         po.auto = false;
         List<PubPO> pubs = bo.findChannels(po.appid);
         if(pubs.size() != 0)
         {
        	po = pubs.get(0);
        	data.put("pubkey", po.pubkey);
        	if(params.get("name")!=po.name){
        		po.name = params.get("name");
        		Logger.info("pubautocreate name=%s", po.name);
        		bo.setBean(bo.genSys(PubAccountBO.PUBS,po.pid), po);
        		bo.dopub(po);
                bo.hset("pubid", po.pid, new Gson().toJson(po));
        	}
        	data.put("pubid", po.pid);
            renderJSON(new Result(true,data.toString()));
         }
         Logger.info("pubautocreate name=%s", po.name);
         po.pubkey = MD5Util.md5Hex(po.appid + System.currentTimeMillis());
         po.type = 0;
         po.pid = "XT-" + UUID.randomUUID();
         po.time = System.currentTimeMillis();
         po.state = 2;
         po.states = "启用";
         po.cssb = false;
         po.allssb = true;
         po.reply = true;
         po.app = "1";  
         
         bo.saveChannel(po);    
         data.put("pubid", po.pid);
         data.put("pubkey", po.pubkey);
         renderJSON(new Result(true,data.toString()));
    }
    
    
    public static void updatePubName(){
    	
    }
    /**
     * 给mcloud自动创建公共号客服通道
     * @param remind
     * @param auto
     * @throws Exception
     */
    public static void pubCustSerCreate() throws Exception {
    	 PubPO po = new PubPO();
    	 JSONObject data = new JSONObject();
         po.appid = params.get("appid");
         po.name =  params.get("name");
         po.remind = false;
         po.auto = false;
         List<PubPO> pubs = bo.findCustSerChannels(po.appid);
         if(pubs.size() != 0)
         {
        	po = pubs.get(0);
        	if(params.get("name")!=po.name){
        		bo.setBean(bo.genSys(PubAccountBO.PUBS,po.pid), po);
        	}
        	data.put("pubid", po.pid);
            data.put("pubkey", po.pubkey);
            renderJSON(new Result(true,data.toString()));
         }
         Logger.info("pubcustsercreate name=%s", po.name);
         po.pubkey = MD5Util.md5Hex(po.appid + System.currentTimeMillis());
         po.type = 0;
         po.pid = "XT-" + UUID.randomUUID();
         po.time = System.currentTimeMillis();
         po.state = 2;
         po.states = "启用";
         po.cssb = false;
         po.allssb = true;
         po.reply = true;
         po.app = "1";  
         
         bo.saveCustSerChannel(po);    
         data.put("pubid", po.pid);
         data.put("pubkey", po.pubkey);
         renderJSON(new Result(true,data.toString()));
    }
    
    /**
     * 公共号菜单
     *
     * @throws Exception
     */
    public static void pubmenu() throws Exception {

        String mid = params.get("mid");
        String pid = params.get("pid");
        String token = params.get("sign");
        String menu = params.get("menu");
        if (StringUtils.isEmpty(mid) || StringUtils.isEmpty(pid) || StringUtils.isEmpty(menu)) {
            errorText(500, "必须传入参数:pid,mid,menu");
        }
        
        PubPO po = bo.findPub(pid);
        
        if (null == po || StringUtils.isEmpty(po.pid)) {
        	String error = String.format("公共号[%s]不存在或者已作废!", pid);
        	errorText(400, error);
		}
        
        if (Play.mode == Play.Mode.PROD) {
            if (!McloudBO.encryptOk(mid, pid, token)) {
                Logger.error("公司签名无效:mid=%s,pid=%s,sign=%s", mid, pid, token);
                errorText(503, "公司签名无效!");
            }
        }
        
        try {
            new Gson().fromJson(menu, Menus.class);
        } catch (JsonSyntaxException e) {
        	Logger.error(e, e.getMessage());
        	errorText(400, "没有传入规定的参数,或是错误JSON格式");
        }
        
        bo.editMenu(pid, menu);
        String eid = StringUtils.isEmpty(po.mid)?"all":po.mid;
        xt.notifyChange(pid, eid);
        Logger.info("菜单创建成功...【%s(%s)】", mid, pid);
        renderJSON(new Result2(true));
    }


    public static void pubreply() throws Exception {
        String msg = params.get("msg"), pid = params.get("pid");
        Logger.info("pubreply  %s= %s", pid, msg);
        MessageHead mh = new MessageHead();
        mh.id = MD5Util.randomString(8);
        mh.model = 4;
        mh.msgType = 2;
        mh.text = msg;

        Map<String, String> param = new HashMap<String, String>();
        param.put("name", "ssb");
        param.put("nb", "true");
        param.put("msg", mh.id);
        param.put("score", "5");
        param.put("tp", "3");
        bo.saveMsg(pid, mh);
        bo.createReplyRule(pid, "3", "ssb", 5);
        bo.editReply(pid, param);
    }

    /**
     * 讯通消息或事件,应答到讯通,或规则推送
     * XT -->PubReplyJob   --> PubPushJob
     */
    public static void xtsend(String from, String to, String msgid) throws Exception {

        Logger.info("fromxt %s to %s,msgid %s", from, to, msgid);
        // 发给公共号,文件传输助手
        if (StringUtils.isEmpty(to)
                || to.equals("XT-0060b6fb-b5e9-4764-a36d-e3be66276586")
                || !to.startsWith("XT-")) {
            ok();
        }

        new PubReplyJob(from, to, msgid).now();
        ok();

    }

    public static List toList(String from) {
        List us = new ArrayList();
        us.add(from);
        return us;
    }

    /**
     * 企业公共号列表
     *
     * @param mid
     * @throws Exception
     */
    public static void xtpubs(String mid, boolean app) {
        try {
			renderJSON(bo.xtPubs(mid, app ? 1 : 0));
		} catch (Exception e) {
			renderJSON(new JSONObject("error", e.getMessage()));
		}

    }
    
    /**
     * 公共号信息
     *
     * @param pid
     * @throws Exception
     */
    public static void xtpub(String pid) throws Exception {
    	
    	if (StringUtils.isEmpty(pid)) {
			renderJSON(new Result2("pid is null or empty!"));
		}
    	
        String p = bo.hget("pubid", pid);
        if (p == null) {
            PubPO pub = bo.findPub(pid);
            if (pub == null) {
                renderJSON("");
            }
            bo.dopub(pub);
            p = new Gson().toJson(pub);
            bo.hset("pubid", pid, p);

        }

        renderJSON(p);

    }

    /**
     * 查某用户是哪些公共号的发言人 by userid 查某公共号的发言人 by pid
     *
     * @param userid,pid
     * @throws Exception
     */
    public static void xtspokesman(String userid, String pid) throws Exception {
        //Logger.debug("xtuserpubs userid=%s,pid=%s", userid, pid);
        if (userid != null) {
            String p = bo.hget("spokeuser", userid);
            if (p == null) {
                p = new Gson().toJson(bo.findAssUser(userid));
                bo.hset("spokeuser", userid, p);
            }
            renderJSON(p);
        }

        if (pid != null) {
            String p = bo.hget("spokepid", pid);
            if (p == null) {
                p = new Gson().toJson(bo.findGroupUser(pid, Sess.ASS_SAY));
                bo.hset("spokepid", pid, p);
            }
            renderJSON(p);
        }

        renderText("");

    }

    /**
     * 用户是否公共号的发言人
     *
     * @param userid
     * @param pid
     * @throws Exception
     */
    public static void xtisspokesman(String userid, String pid) throws Exception {
        Logger.debug("xtuserpub userid=%s,pid=%s", userid, pid);
        List<String> pubs = bo.findAssUser(userid);
        renderText(pubs.contains(pid));

    }

    public static void xtpubmenu(String pid) throws Exception {
        Logger.debug("xtpubmenu pid=%s", pid);
        String menu = bo.getMenu(pid);
        renderText(menu);

    }

    public static void xtssb(String pid, String uid, int data) throws Exception {
        Logger.info("xtssb%d pid=%s,uid=%s", data, pid, uid);
        JSONObject user = open.userInfo(uid);

        if (user == null || data < 0 || data > 2) {
            ok();
        }
        String mid = user.getString("eid");

        if (data == 2) { //新讯通用户
            /*String eidName=(String)Cache.get("eName:"+mid);
            if (eidName==null) {
                eidName=open.getEidName(mid);
                //新企业,复制新闻公告
                PubPO pub = bo.findPub("XT-10002");
                pub.tmplid = pub.pid;
                pub.pid = "XT-" + UUID.randomUUID();
                pub.mid = mid;
                pub.pubkey = "";

                pub.state = 2;
                pub.states = "启用";
                pub.tmpl = false;
                pub.type = 2;
                AcctVO vo=new AcctVO();
                vo.uid=uid;

                bo.savePub(pub, vo);
                bo.editMenu(pub.pid, bo.getMenu(pub.tmplid));
                //复制订阅应答
                Map<String, String> reply = bo.getReply("XT-10002", "3", "subResName");
                if (reply!=null ){
                    bo.editReply(pub.pid,reply);
                }
                //TODO 设置本人为管理员和发言人
                pubAuthService.addPubAdmin(pub.pid, uid); // 设置本人为公共号的管理员
                pubAuthService.addAssUser(pub.pid, uid);  // 设置本人为公共号的发言人
            }*/

            bo.newuser(mid, uid);

            List<PubPO> ps = bo.xtPubs(mid, -1); // 企业或订阅公共号
            List<String> to = toList(uid);
            for (PubPO pub : ps) {
            	if (pub.allssb) { // 默认订阅
            		bo.add1(PubAccountBO.PUB_SUBCOUNT, pub.pid);
            		appSubService.subApp(pub.pid, to);
                    if (!mid.equals("10101")) {
                        dossbReply(pub.pid, uid, pub.name);
                    }
                }
            }
            
            /**
             * 李杨晶的需求: KSSP-18690 新注册用户使用待办通知进行引导 
             * 
             * 新注册的用户的待办通知出现[XXX @你]的待办消息
             * 
             */
            try {
            	final List<String> toUid = to;
            	String newUserPid = Play.configuration.getProperty("new.user.pid", "XT-10000");
                String msgId = (String) new MsgService.RuleService() {
    				@Override
    				public Object doRule(GwHead msg, Map<String, String> rule) throws PubException {
    					return msgService.sendNotifyMsgByTpl(msg, rule, toUid);
    				}
    			}.sendMsgByRule(newUserPid, "newUser");
    			JSONObject result = new JSONObject("sourceMsgId", msgId);
    			result.put("pubId", newUserPid);
    			// 建立该条消息和公共号的对应关系
    			reportBO.saveMsgPub(msgId, newUserPid);
    			// 把该首条待办消息保存起来
    			toDoMsgBO.addFirstTodoMsg(msgId);
    			renderJSON(result);
			} catch (PubException e) {
				String message = e.getMessage();
				if (StringUtils.isNotEmpty(message)) {
					renderJSON(new Result2(message));
				}
			} catch (Exception e) {
				renderJSON(new Result2(e.getMessage()));
			}
            
            /*JSONObject msg = new JSONObject("task", "task0");
            msg.put("type", 1);
            msg.put("id", uid);
            // MQ发送监控新手进圈事件
            MQSendMsg.send(PubUtils.PUB_EXCHANGE_NAME, "XT", msg.toJSONString());
            */

        } else {// 1 订阅,0取，
            PubPO pub = bo.findPub(pid);
            if (pub == null || pub.state != 2 || !pub.cssb) {
                ok();
            }
            if (data == 1) {
                bo.add1(PubAccountBO.PUB_SUBCOUNT, pub.pid);
//              xt.subpub(pid, mid, toList(uid));
                appSubService.subApp(pid, toList(uid));
                dossbReply(pid, uid, pub.name);
            } else {
                appSubService.unSubApp(pid, toList(uid));
            }


        }
        ok();

    }

    /**
     * 公共号历史消息
     *
     * @param pid  公共号
     * @param page 页码，0=第一页
     * @throws Exception
     */
    public static void xtpubhis(String pid, int page) throws Exception {

        List<JSONObject> msgs = new ArrayList<JSONObject>();
        for (String id : bo.listSndMsg(pid, page * 10, (page + 1) * 10 - 1)) {
            JSONObject msg = xt.findMessage(id);
            if (msg == null) {
                continue;
            }
            msgs.add(msg);
        }
        renderJSON(msgs);
    }

    private static void dossbReply(String pid, String uid, String pubName) {
    	try {
            JSONObject msg = new JSONObject("fromUserId", uid);//xt.findMessage(m);
            msg.put("msgType", 9);
            msg.put("content", "订阅");
            msg.put("params", new JSONObject("eventKey", "ssb"));
            PubReplyJob rjob = new PubReplyJob();
            rjob.setMsg(rjob.toGw(msg, pid, pubName));
            //玩转云之家,最后显示订阅应答
            if (StringUtils.startsWith(pid, "XT-797bc9fe-ee06")) {
                rjob.in(8);
            } else if("XT-10000".equals(pid)) {
                rjob.now();
            } else {
            	rjob.in(4);
            }
		} catch (Exception e) {
			renderJSON(new Result2(e.getMessage()));
		}
// 		
    }


    public static void url(String url, String uid) throws Exception {
        String u = bo.getUrl(url);
        if (u == null) {

            renderHtml("地址不存在!");
        }

        throw new Redirect(u);
        /*
         * JSONObject user =null; if(uid!=null){ user =new JSONObject(); //xt.userInfo(null,uid);
         * user.put("name", "test"); user.put("year", 2014); }
         *
         *
         * if(user==null){ }else{
         *
         * //Map<String, String> o = XedisClient.obj2map(user); //Logger.debug("redirect arg=%s",
         * o.values()); throw new Redirect(u+"?user="+user.toJSONString()); }
         */

    }

    public static void findMessage(String id) throws Exception {
        JSONObject msg = xt.findMessage(id);
        renderJSON(msg);
    }

    public static void searchUsers(String mid, String name, String uid, String oid) throws Exception {

        if (StringUtils.isNotEmpty(uid)) {
            JSONObject user = open.userInfo(uid);
            renderJSON(user);
        }
        if (StringUtils.isNotEmpty(oid)) {
            JSONObject user = open.userInfo(open.getpersonidbyopenid(oid));
            renderJSON(user);
        }
        if (StringUtils.isEmpty(mid)) {
            mid = PublicAccount.ADMINEID;
        }
        if (StringUtils.isNotEmpty(name)) {
            JSONArray users = open.searchpersons(mid, name);
            renderJSON(users);

        }

        ok();

    }
    public static void searchPubs(String name) throws Exception {

        Jedis jedis = JedisClient.getClient();
        Set<String> mids = jedis.smembers(bo.genSys(
                PubAccountBO.PUBMID, "mid"));// 已审核企业
        HashMap<String,String> ret=new HashMap<String,String>();
        for (String mid : mids) {

            Set<String> pids = jedis.smembers(bo.genSys(
                    PubAccountBO.PUBMID, mid));// 企业已审核
            // 每个公共号
            for (String pid : pids) {
                PubPO pubPO = bo.findPub(pid);
                if (pubPO.name == null)
                    continue;
                //Logger.info("pubname=%s",pubPO.name);
                if (StringUtils.contains(pubPO.name, name)) {
                    ret.put(pid, pubPO.name);
                   // Logger.info("pubname===%s", pubPO.name);
                }
            }
        }

        renderJSON(ret);

    }

    public static void rest(String url, String method) throws Exception {
        boolean post = false;
        if (StringUtils.endsWithIgnoreCase("post", method)) {
            post = true;
        }

        WS.WSRequest ws = WS.url(url);
        ws.timeout("10s");
        WS.HttpResponse res = post ? ws.post() : ws.get();

        if (res.success()) {
            renderText(res.getString());
        }

        Logger.error("req api error. code %s", res.getStatus());
        errorText(res.getStatus(), res.getString());
    }

    //健康状态内部检测
    public static void getstatus() throws Exception {
        JSONObject status = new JSONObject();
        StringBuilder data = new StringBuilder();
        Boolean ok = true;
        String desc = "OK";
        try {

            if (!StringUtils.equalsIgnoreCase(JedisClient.myClient().ping(), "PONG")) {
                ok = false;
                desc = "Redis Not Connected！";
            }
        } catch (Exception e) {
            ok = false;
            desc = "Redis Not Connected！";
        }

        try {
            long bt = System.currentTimeMillis();
            List<String> ids = new ArrayList<String>();
            ids.add("50936aa924ac46fb95000f31");
            ids.add("5093602b24ac46fb94fb7487");
            ids.add("50936e8724ac46fb9501cd1b");
            ids.add("509371b024ac46fb950312dc");
            ids.add("509372bb24ac46fb9503a5c6");
            ids.add("251e74d8-05a9-11e4-9273-dc8e3044c615");
            ids.add("5093619024ac46fb94fc3b76");
            
            JSONArray users = open.getpersonsbyids(ids);
            data.append("opentime=").append(System.currentTimeMillis() - bt).append(";");
            if (users == null || CollectionUtils.intersection(ids, users.strList("id")).isEmpty()) {
                ok = false;
                desc = "Open Server Not Connected！";
            }
        } catch (Exception e) {
            ok = false;
            desc = "Open Server Not Connected！";
        }
        
        int openTokenStatus = monitorService.getOpenTokenStatus();
        
        if (1 == openTokenStatus && ok) {
			desc = "OpenToken from Open Authentication is failed or timeout, Open Server Not Connected!";
			ok = false;
		}
        
        int ticketStatus = monitorService.getTicketStatus();
        
        if (1 == ticketStatus && ok) {
        	desc = "Ticket from Pubacc request is failed or timeout!";
			ok = false;
		}
        
        int appAuth2Status = monitorService.getAppAuth2Status();
        
        if (1 == appAuth2Status && ok) {
        	desc = "AccessToken from Pubacc AppAuth2 request is failed or timeout!";
			ok = false;
		}
        
        try {
            long bt = System.currentTimeMillis();
            String testXt = xt.saveMessage("test", 2, "test", "", 0);
            data.append("xttime=").append(System.currentTimeMillis() - bt).append(";");
            if (StringUtils.isEmpty(testXt)) {
                ok = false;
                desc = "Message Server Not Connected！";
            }
        } catch (Exception e) {
            ok = false;
            desc = "Message Server Not Connected！";
        }

        data.append("maxmem=").append(Runtime.getRuntime().maxMemory()).append(";");
        data.append("freemem=").append(Runtime.getRuntime().freeMemory()).append(";");
        data.append("totalmem=").append(Runtime.getRuntime().totalMemory()).append(";");
        data.append("poolsize=").append(Invoker.executor.getPoolSize()).append(";");
        data.append("activecount=").append(Invoker.executor.getActiveCount()).append(";");
        data.append("taskcount=").append(Invoker.executor.getTaskCount()).append(";");
        data.append("queuesize=").append(Invoker.executor.getQueue().size()).append(";");
        data.append(JedisClient.getstatus());
        data.append(bo.getstatus());
        
        data.append("openTokenStatus=").append(openTokenStatus).append(";");
        data.append("ticketStatus=").append(ticketStatus).append(";");
        data.append("appAuth2Status=").append(appAuth2Status).append(";");
        
        renderJSON(last(status, ok, desc, data));
    }
    
    private static String last(JSONObject status, Boolean ok, String desc, StringBuilder data) {
    	status.put("ok", ok);
        status.put("desc", desc);
        status.put("data", data.toString());
        return status.toString();
    }

    public static void pluginStatus() throws Exception {
        String out = "";
        for (PlayPlugin p : Play.pluginCollection.getEnabledPlugins()) {
            try {


                String s = p.getStatus();
                if (s != null) {
                    out += s;
                }
            } catch (Exception e) {
                Logger.error(e.getMessage());
            }
        }
        renderText(out);
    }

    /**
     * 网关发送消息,应答到讯通,或规则推送
     * GW -->PubReplyJob   --> PubPushJob
     */
    public static void gwsend() throws Exception {

        MessageFrom sendfrom;
        MessageVO msgs = null;
        Gson gs = new Gson();

        params.getRootParamNode();
        String body = params.data.get("body")[0];


        // Logger.info("pubsend body SIZE %d ", );
        if (body.length() > 1000000) {
            errorText(5002, "传入数据长度超过了1M,请裁剪!");
        }
        try {
            msgs = gs.fromJson(body, MessageVO.class);
        } catch (Exception e) {
            errorText(5000, "没有传入规定的参数,或是错误JSON格式", e.getMessage());
        }

        sendfrom = msgs.from;

        Logger.info("gwsend %s ", body);

        // 验证公司签名
        if (sendfrom == null || sendfrom.no == null || sendfrom.pub == null
                || sendfrom.time == null || sendfrom.pubtoken == null || sendfrom.nonce == null) {
            errorText(5000, "必须传入参数:from{no,pub,time,nonce,pubtoken}");
        }

        String from = sendfrom.pub;
        PubPO pub = bo.findPub(from);
        if (pub == null || pub.state != 2) {
            errorText(5001, "公共号不存在或未审核！", from);
        }
        //公共号密钥验证
        if (!StringUtils.isEmpty(pub.pubkey)) {
            String ptoken = MD5Util.sha(pub.pubkey, sendfrom.no, sendfrom.pub, sendfrom.time, sendfrom.nonce);
            if (!ptoken.equalsIgnoreCase(sendfrom.pubtoken)) {
                errorText(5004, "公共号密钥验证失败！", "pub %s token %s sha %s", sendfrom.pub, sendfrom.pubtoken, ptoken);
            }
        }
        new PubReplyJob(msgs.gwmsg).now();

        ok();


    }

    /**
     * 二维码登录
     */
    public static void qrlogin() throws Exception {

        String type = params.get("type");
        if (!"2".equals(type)) {
            ok();
        }

        Logger.info("qrlogin=%s,head=%s", params.allSimple(), request.headers);

        String token = params.get("token");

        AcctVO vo = Cache.get("token_" + token, AcctVO.class);
        if (vo == null) {
            errorText(400, "会话已经过期，请刷新登录页面!", token);
        }
        Http.Header h = request.headers.get("opentoken");
        if (h == null) {
            vo.errmsg = "没用户token，请重新登录云之家手机客户端!";
            Cache.set("token_" + token, vo, "10min");
            errorText(400, vo.errmsg, token);
        }
        String mid = params.get("3gNo");
        String ip = request.remoteAddress;
        if (ip == null) ip = "127.0.0.1";
        String openToken = h.value();
        mid = RSAUtils.decode(mid, "xtweb102");

        JSONObject user = xt.authTokenUser(ip, mid, openToken);
        if (user == null) {
            vo.errmsg = "没查询到用户，请重新登录云之家手机客户端!";
            Cache.set("token_" + token, vo, "10min");
            errorText(400, vo.errmsg, token);
        }
        Logger.info("qrlogin mid=%s,user=%s", mid, user.toJSONString());

        //管理员
        String uid = user.getString("id");
        String openId = user.getString("wbUserId");
        Set<String> pids = bo.findAdminOpenUser(openId);
        if (pids.isEmpty()) {
            openId = open.userInfo(uid).getString("openId");
            pids = bo.findAdminOpenUser(openId);
            if (pids.isEmpty()) {
                vo.errmsg = "你不是公共号管理员,请联系企业管理员!";
                Cache.set("token_" + token, vo, "10min");
                errorText(400, vo.errmsg, openId);
            }
        }
        bo.auth(vo, pids, null);

        vo.uid = uid;
        vo.openid = openId;
        vo.username = user.getString("name");

        Cache.set("token_" + token, vo, "10h");
        ok();

    }

    /**
     * 二维码订阅
     */
    public static void qrreg() throws Exception {
        Logger.info("start qrreg...");
        /**手机端确认可登录*/
        String type = params.get("type");
        if (!"2".equals(type)) {
            ok();
        }

        PubUtils.debug("admin", "qrreg=%s,head=%s", params.allSimple(), request.headers);

        String openToken = PubUtils.getHeader(request, "opentoken");
        if (openToken == null) error("not find opentoken");

        String mid = params.get("3gNo");
        String pid = params.get("pid");
        String ip = request.remoteAddress;
        if (ip == null) ip = "127.0.0.1";

        PubPO pub = bo.findPub(pid);
        if (pub == null || pub.state != 2 || !pub.cssb) {
            errorText(403, "公共号不能个人订阅，只能是管理员订阅", pid);
        }

        //openToken="[elJOMmFuQ2srmJub9cQyKyljt_geVa96JkfTZjjhDEFPgh09rcar5VUV7aq2D-CQ4memo17qjPqP1-cwXjtEVxcw9j9Gds7nhdezZtKbV5wZM-c02d5pgNHqm7rPPRN-IhJKJ-aV17HAKD0zgUXWI1tk58u3LIfaKV6F35O1TsrjZbFPpC7zYw";
        mid = RSAUtils.decode(mid, "xtweb102");
        Logger.info("qrreg mid=%s, pid=%s", mid, pid);
        JSONObject user = xt.authTokenUser(ip, mid, openToken);
        if (pub.type == 2 && !pub.mid.equals(mid)) {
            errorText(403, "非公共号企业员工，不能订阅公共号", pid);
        }
        String uid = user.getString("id");
        Logger.info("qrreg user=%s", user.toString());
        bo.add1(PubAccountBO.PUB_SUBCOUNT, pid);
//      xt.subpub(pid, mid, toList(uid));
        appSubService.subApp(pid, toList(uid));
        dossbReply(pid, uid, pub.name);
        Logger.info("订阅公共号成功：" + pub.name);
        renderText("订阅公共号成功：" + pub.name);

    }

    /**
     * 二维码是否登录
     */
    public static void islogin() throws Exception {

        String token = params.get("token");
        AcctVO vo = Cache.get("token_" + token, AcctVO.class);
        renderJSON(vo);

    }
    
    /**
     * 获取图像验证码
     * @throws Exception
     */
    public static void getCheckCode() throws Exception {
    	JSONObject ret = new JSONObject();
//    	String url=CommonEmpServerConfig.getInstance().getOpenCodeServerURL()+"opencode/captcha/getcode";
		String url = opencodeUrl+"/opencode/captcha/getcode";
		JSONObject jsonObject=new JSONObject();
		jsonObject.put("type", CODETYPE);
		jsonObject.put("key", CODEKEY);
		String res = "";
		try{
			 res = HttpClientUtil.postJSON(url, jsonObject);
		} catch (HttpException e) {
			String error = e.getMessage() + " response " + e.getStatusCode() + "!";
			Logger.error(e, error);
		} catch (Throwable e) {
			Logger.info(e.getMessage());
		}
		
		String imgUrl =imgeOutUrl+"/openaccess/captcha/img/";
		
		Logger.info("%s return result is %s.", url, res);
		JSONObject result = JSONObject.parseObject(res); 
		JSONObject jsonObj=(JSONObject) result.get("data");
		Logger.info("getCheckCode[%s]", jsonObj.toJSONString());
		if(jsonObj!=null){
			imgUrl = imgUrl+jsonObj.getString("id");
			ret.put("data", imgUrl);
			ret.put("id", jsonObj.getString("id"));
			ret.put("key", CODEKEY);
		}
        renderJSON(ret);

    }
    /**
     * 建立业务上下文,供讯通手机调用
     * <p/>
     * eid   企业号
     * appid 应用号
     * sessionid
     */
    public static void createcontext() {
        JSONObject ret = new JSONObject();
        ret.put("success", false);
        ret.put("errorCode", 403);
        ret.put("data", "");

        try {
        	Map<String, String> context = params.allSimple();
            Logger.info("PublicAPI.createcontext context is " + context);
            boolean vk = false;

            Http.Header h = request.headers.get("opentoken");
            //errorText(403, "缺少令牌opentoken");
            if (h == null) {
                h = request.headers.get("accesstoken");
                if (h == null) {
                    ret.put("error", "缺少令牌opentoken");
                    renderJSON(ret);
                }
                //万科员工
                vk = true;
            }

            String eid = context.get("eid");
            String appid = context.get("appid");
            if (eid == null || appid == null) {

                JSONObject body = JSON.parseObject(context.get("body"));
                if (body == null) {
                    ret.put("error", "缺少企业号eid和应用号appid");
                    renderJSON(ret);
                }
                eid = body.getString("eid");
                appid = body.getString("appid");
                if (eid == null || appid == null) {
                    ret.put("error", "缺少企业号eid和应用号appid");
                    renderJSON(ret);
                }
                context.put("eid", eid);
                context.put("appid", appid);
            }
            
            Logger.info("PublicAPI.createcontext context is " + context + "after opera.");
            context.remove("body");
            context.remove("action");

            JSONObject appo = (bo.getapp(appid));

            if (appo == null || StringUtils.isEmpty(appo.getString("appurl"))) {
                ret.put("error", "无效应用号appid");
                renderJSON(ret);
            }

            String ip = request.remoteAddress, id;
            if (ip == null) ip = "127.0.0.1";
            String openToken = h.value();
            //openToken="[elJOMmFuQ2srmJub9cQyKyljt_geVa96JkfTZjjhDEFPgh09rcar5VUV7aq2D-CQ4memo17qjPqP1-cwXjtEVxcw9j9Gds7nhdezZtKbV5wZM-c02d5pgNHqm7rPPRN-IhJKJ-aV17HAKD0zgUXWI1tk58u3LIfaKV6F35O1TsrjZbFPpC7zYw";
            eid = RSAUtils.decode(eid, "xtweb102");
            JSONObject user = null;
            if (vk) {
                user = PubUtils.vankeUser(openToken); // 旧版万科逻辑
            } else {
//              user = xt.authTokenUser(ip, eid, openToken); // 2015-11-10 openToken验权直指open, 不再经过xuntong
            	String uid = null;
            	try {
            		uid = open.openToken(openToken);
    			} catch (Exception e) {
    				ret.put("error", "openToken从open服务器出现事故, " + e.getMessage());
    				renderJSON(ret);
    			}
            	
            	if (StringUtils.isEmpty(uid)) {
    				ret.put("error", "错误的openToken!");
    				renderJSON(ret);
    			}
            	
            	user = open.userInfo(uid);
                if (user == null) {
                    ret.put("error", "无法找到令牌对应用户openid");
                    renderJSON(ret);
                }
            	
            }
            
            if (user == null) {
                ret.put("error", "无法找到令牌对应用户");
                renderJSON(ret);
            }

            manage.addOpen(appid, user.getString("id"));//应用打开次数

            context.put("username", user.getString("name"));
            context.put("oid", user.getString("oId"));
            context.put("openid", user.getString("oId"));
            context.put("xtid", user.getString("id"));
            context.put("userid", user.getString("wbUserId"));
            context.put("networkid", user.getString("wbNetworkId"));
            if (StringUtils.isEmpty(context.get("networkid")))
                context.put("networkid", context.get("eid"));


            id = MD5Util.md5Hex(user.getString("oId") + System.currentTimeMillis());
            //context.put("ticket", id);
            Logger.info("create ticket=%s,appid=%s", id, appid);
            Cache.set("openauth_context_" + id, context, "2h");
            ret.put("data", new JSONObject("ticket", id));
            ret.put("success", true);
            ret.put("errorCode", 0);
            ret.put("error", "");
            monitorService.setTicketStatus(0);
            try{
            	JSONObject data = new JSONObject();
    			data.put("appid", appid);
    			data.put("oid", user.getString("oId"));
    			data.put("eid", eid);
    			data.put("source", 3);
    			data.put("time", System.currentTimeMillis());
    			JSONObject result = new JSONObject();
    			result.put("datatype", 1);
    			result.put("data",data);
            	MQSendMsg.send("TICKET_GENERATE_EXCHANGE", "", result.toJSONString(), false);
            }catch(Exception e){
            	
            }
            renderJSON(ret);
            //TODO 统计应用打开月活
		} catch (Throwable e) {
			monitorService.setTicketStatus(1); // 报警
			Logger.error(e, e.getMessage());
			ret.put("errorCode", 500);
			ret.put("error", "ticket服务异常!" + e.getMessage());
            renderJSON(ret);
		}

    }

    /**
     * 读取业务上下文,供轻应用调用
     *
     * @param ticket 门票
     * @return sessionid
     */
    @SuppressWarnings("unchecked")
	public static void getcontext(String ticket, String access_token, String appid, String eid) throws Exception {
    	response.accessControl("*");
    	Logger.info("accesser get context ticket=" + ticket);
        String aid = null;
        if(ticket.contains("APPURLWITHTICKET"))
        {
        	Logger.info(ticketurl);
        	WSRequest getcontexturl = WS.url(ticketurl + "/ticket/public/tickettocontext","UTF-8");
            //getcontexturl.setParameter("access_token", access_token);
            //getcontexturl.setParameter("appid", appid);
            //getcontexturl.setParameter("ticket", ticket);
            JSONObject param = new JSONObject();
            param.put("access_token", StringUtils.isEmpty(access_token)?"BLANK":access_token);
            param.put("appid", appid);
            param.put("ticket", ticket);
            getcontexturl.body(param.toJSONString());
            JSONObject result = fromJson(getcontexturl);	  
            if (null != result && result.getBooleanValue("success")) {
            	JSONObject d = result.getJSONObject("data");
            	if(d.containsKey("xtid")){
            		d.remove("xtid");
            	}
            	Logger.info("返回用户上下文" + d.toJSONString());
            	renderJSON(d.toJSONString());
    		}else
    		{
    			errorText(300, result.toJSONString(), null);
    		}
        }else
        {
        	//获得appid
            if (access_token != null) {
                aid = Cache.get("access_token_" + access_token, String.class);
                if (aid == null){
                	WSRequest getcontexturl = WS.url(openapi + "/openapi/innerticket/getaccesstoken","UTF-8");
                    getcontexturl.setParameter("accesstoken", access_token);
                    JSONObject result = fromJson(getcontexturl);	  
                    if (null != result && result.getBooleanValue("success")) {
                    	aid = result.getJSONObject("data").getString("appid");
            		}else
            		{
            			errorText(401, "令牌过期access_token", access_token);
            		}
                }
                if(aid == null)
                {
                	errorText(401, "令牌过期access_token", access_token);
                }
                if (appid != null) {
                    //消费者验证

                    JSONObject appo = bo.getapp(aid);

                    if (appo == null) {
                        errorText(401, "无效应用号令牌", aid);
                    }
                    boolean err = true;
                    for (String no : bo.getConsumer(appid)) {
                        JSONObject jo = JSON.parseObject(no);
                        if (StringUtils.equals(jo.getString("consumer"), aid)) {
                            if (StringUtils.isEmpty(eid) || !jo.containsKey("eid") ||
                                    (jo.containsKey("eid") &&
                                            StringUtils.contains(jo.getString("eid"), eid + ","))) {
                                err = false;
                                break;
                            }

                        }
                    }
                    if (err)
                        errorText(401, "服务消费者令牌无权调用应用", access_token);

                    JSONObject ret = new JSONObject("appid", appid);
                    ret.put("consumer", aid);
                    ret.put("eid", eid);
                    ret.put("name", appo.getString("appname"));

                    Logger.info("consumer %s", ret);
                    if (params.get("callback") != null) {
                        renderJSONP(ret);
                    }
                    renderJSON(ret);

                }

                if (StringUtils.isEmpty(ticket)) {
                    //验证token
                    JSONObject ret = new JSONObject("appid", aid);
                    if (params.get("callback") != null) {
                        renderJSONP(ret);
                    }
                    renderJSON(ret);

                }

            }
            if (StringUtils.isEmpty(ticket)) {
                errorText(400, "缺少门票ticket");
            }
            Logger.info("getcontext %s", ticket);

            //WEB登录模拟用户
            if (ticket.startsWith("token")) {
                String token = ticket.substring(5);
                AcctVO vo = Cache.get("token_" + token, AcctVO.class);
                if (vo == null) {
                    errorText(401, "请登录");
                }
                Map<String, String> context = new HashMap<String, String>();
                context.put("username", vo.name);
                context.put("openid", vo.pid);
                context.put("eid", vo.mid);
                renderJSON(context);

            }
            Map<String, String> context = Cache.get("openauth_context_" + ticket, Map.class);
            if(context == null)
            {
            	context = new HashMap<String,String>();
            	WSRequest getcontexturl = WS.url(openapi + "/openapi/innerticket/getcontext","UTF-8");
                getcontexturl.setParameter("ticket", ticket);
                JSONObject result = fromJson(getcontexturl);	  
                
                if (null != result && result.getBooleanValue("success")) {
                	Iterator<String> it = result.getJSONObject("data").keySet().iterator();           
                    while(it.hasNext())
                    {
                    	String key = it.next();
                    	String value = result.getJSONObject("data").getString(key);
                    	context.put(key, value);
                    }
        		}
            }
            if(context == null || context.size() == 0)
            {
            	 errorText(405, "无效或过期门票", ticket);
            }
            JSONObject appo = bo.getapp(context.get("appid"));

            if (appo == null || StringUtils.isEmpty(appo.getString("appurl"))) {
                errorText(400, "无效应用号appid", ticket);
            }

            //除了轻应用平台,其它轻应用需要授权access_token
            String appurl = appo.getString("appurl").toLowerCase();
            if (Play.mode.isProd() && StringUtils.indexOfAny(appurl, new String[]{"kdweibo", "xuntong", "kingdee"}) == -1) {
                if (access_token == null || aid == null) {
                    //只返回eid方便路由。 errorText(401, "需要令牌access_token", appid);
                    context.remove("openid");
                    context.remove("username");
                    context.remove("xtid");
                    context.remove("oid");

                } else if (!StringUtils.equals(appo.getString("appid"), aid)) {
                	Logger.info("appo[%s]  aid[%s]", appo.toJSONString(),aid);
                    errorText(401, "无效令牌access_token", aid);
                }

            }
            
            context.remove("deviceId");
            context.remove("deviceType");
            //context.remove("oid");

            if (params.get("callback") != null) {
                renderJSONP(context);
            }
            renderJSON(context);
        }
        

    }

    private static JSONObject fromJson(WSRequest url) {
        try {
            url.timeout("5s");
            HttpResponse res = url.post();
            String ret = res.getString();
            JSONObject json = JSON.parseObject(ret);
            return json;

        } catch (Exception e) {
            Logger.error("openapi getcontext error %s.", e.getMessage());
        }
        return null;
    }
    
    private static void renderJSONP(Object ret) {
        renderText(params.get("callback") + "(" + new Gson().toJson(ret) + ")");
    }


    /**
     * 生成令牌,供轻应用调用
     * grant_type	 是	 获取access_token填写client_credential
     * appid	 是	 第三方用户唯一凭证
     * secret	 是	 第三方用户唯一凭证密钥，即appsecret
     * <p/>
     * 返回说明
     * 正常情况下，微信会返回下述JSON数据包给公众号：
     * {"access_token":"ACCESS_TOKEN","expires_in":7200}
     * 参数	说明
     * access_token	 获取到的凭证
     * expires_in	 凭证有效时间，单位：秒
     * <p/>
     * 错误时微信会返回错误码等信息
     */

    public static void token(String grant_type, String appid, String secret, String token, String ticket, String key) throws Exception {
    	if (StringUtils.isEmpty(grant_type)) {
    		errorText(400, "grant_type is null or empty!");
		}
    	
    	String callback = params.get("callback");
    	boolean jsonp = StringUtils.isNotEmpty(callback);

    	if (grant_type.equalsIgnoreCase("client_credential")) {
    		
    		Logger.info("调用openauth2旧接口..., appid: %s, secret: %s", appid, secret);
    		
    		if (null != bo.getAppRegTime(appid)) { // 新接入的app认证不再使用该接口进行认证
				callback(new Result2("该接口已经作废, 请查看官方文档open.kdweibo.com[服务消费者授权]!"), jsonp);
			}
    		
    		if (StringUtils.isEmpty(appid)) {
        		errorText(400, "appid is null or empty!");
    		}
        	
            JSONObject appo = bo.getapp(appid);

            if (appo == null) {
                errorText(400, "无效应用号appid", appid);
            }
            
            if (StringUtils.isEmpty(secret)) {
        		errorText(400, "secret is null or empty!");
    		}
            
            if (StringUtils.equals(appo.getString("appsecret"), secret)) {
            	JSONObject jo = openAuth2Service.clientCredential(appid);
                callback(jo, jsonp);
            }
            errorText(400, "无效应用密钥", appid);

        }
        if (grant_type.equalsIgnoreCase("valid")) {

        	if (StringUtils.isEmpty(token)) {
        		errorText(400, "token is null or empty!");
    		}
        	
            appid = Cache.get("access_token_" + token, String.class);
            if (StringUtils.isEmpty(appid)) {
                errorText(400, "无效令牌", token);
            }

            JSONObject jo = new JSONObject("appid", appid);

            callback(jo, jsonp);

        }
        
        if (grant_type.equalsIgnoreCase("weibo_credential")) {

        	if (StringUtils.isEmpty(ticket)) {
				errorText(400, "ticket is null or empty!");
			}
        	
            Map<String, String> context = Cache.get("openauth_context_" + ticket, Map.class);
            if (context == null) {
                errorText(400, "无效或过期门票", ticket);
            }
            
            if (StringUtils.isEmpty(key)) {
            	errorText(400, "key is null or empty!");
			}
            
            try {
                String wbtoken = McloudBO.getWBToken(context.get("userid"), context.get("networkid"), key);
                if (wbtoken == null) {
                    errorText(400, "无法生成weibo access_token");
                }

                JSONObject jo = new JSONObject("access_token", wbtoken);
                callback(jo, jsonp);
            } catch (Exception e) {
                errorText(400, e.getMessage());
            }
        }
        errorText(400, "错误的令牌请求");
    }
    
    private static void callback(Object result, boolean jsonp) {
        if (jsonp) {
            renderJSONP(result);
        }
        renderJSON(result);
   }
    
    /**
     * 延伸于token接口的grant_type=client_credential类型的新安全认证方式, 提高安全性
     * 
     * 2015-07-15 把openauth2的client_credential类型接口命为appAuth, 主要是该种类型纯碎是应用级别的授权
     * 			    把openauth2的认证参数转到请求头部, 跨域不放请求头部, 并且对post和get请求方式都兼容
     *            主要是给第三方系统调用时同时地支持第三方的
     *            [post application/x-www-form-urlencoded]和[post application/json],
     *            否则单纯地按之前设计限制为get, 并不能完成满足调用方的使用
     *            
     *            如此之后, 当第三方需要jsonp, 便传入参数callback的, 请求定为get方式即可
     *            同时在不管哪种情况, callback一律不参与md5的签名
     * 
     * @since 2015-07-07
     * @author bingsong_xu
     * @design xueming_zheng
     */
    public static void appAuth2(String callback) {
    	try {
    		response.accessControl("*");
    		response.setHeader("Access-Control-Allow-Headers", "Access-Control-Allow-Origin, X-Request-With, authorization");
			openAuth2Service.clientCredential(request, callback);
		} catch (JSONP e) { // JSONP情况下正常成功时
			renderJSONP(new Result2(true, e.getResult()));
		} catch (JSONPException e) { // JSONP情况下校验失败时
			renderJSONP(e.getResult2());
		} catch (ResultSign e) { // 除JSONP情况下(包括了CORS跨域的情况)正常成功时
			renderJSON(new Result2(true, e.getResult()));
		} catch (PubException e) { // 除JSONP跨域情况下(包括了CORS跨域的情况)校验失败时
			renderJSON(e.getResult2());
		} catch (Throwable e) {
			monitorService.setAppAuth2Status(1);
			Logger.error(e, e.getMessage());
			renderJSON(new Result2(e.getMessage()));
		}
    }
    
    /*

    "user": {   "defaultEmail": "gongli_zeng@kingdee.com",
                "defaultPhone": "18926441109",
                "department": "移动互联平台产品开发部",
                "firstPinyin": "cgl",
                "firstPinyinCode": "245",
                "fullPinyin": "ceng gong li",
                "hasOpened": true,
                "id": "5093608124ac46fb94fba117",
                "jobTitle": "系统设计师",
                "name": "曾功立",
                "petName": null,
                "photoId": "f5f6ad24a63beb12e3572bd6c86a4526",
                "photoUrl": "http://kdweibo.com/space/c/photo/load?id=4e16aa84cce79c8d549d74f7",
                "status": 3,
                "t9PinyinCode": "2364 4664 54",
                "updateTime": "2014-03-15 20:23:09",
                "wbUserId": null
            },
     */

    /**
     * 注册应用,供MCLOUD调用
     *
     * @throws Exception
     */
    public static void appreg() throws Exception {
    	String appId = params.get("appid");
    	if (StringUtils.isEmpty(appId)) {
            ok();
    	}
    	
        Map<String, String> param = params.allSimple();
        param.remove("body");
        param.remove("action");
        Logger.info("appreg=%s", param.toString());
        
        JSONObject jo = new JSONObject();
        jo.putAll(param);
        
        JSONObject app = bo.getapp(appId);
        // 说明是首次注册开通的应用, 注意该条件不能随便去掉, 否则会打乱其他地方(2015-07-01已经使用openauth2的新安全认证机制)的逻辑
    	if (null == app) { 
    		long regTime = System.currentTimeMillis();
    		bo.setAppRegTime(appId, regTime); // 建立关系
    		jo.put("regTime", regTime);
		} else {
	    	Long appRegTime = app.getLong("regTime"); // 后面保存始终以第一次的注册时间为准
	    	if (null != appRegTime) {
				jo.put("regTime", appRegTime);
			}
	    }
    	
        bo.setapp(appId, jo.toJSONString());
        Logger.info("appreg=%s", jo);
        ok();
    }


    /**
     * 查询应用
     *
     * @throws Exception
     */
    public static void applist() throws Exception {
    	String mid = params.get("mid");
        Collection<String> appcol = bo.getappAll();
        JSONArray ja = new JSONArray();
        for (String app : appcol) {
            JSONObject ao = JSON.parseObject(app);
            if (ao == null || ao.isEmpty()
                    || StringUtils.isEmpty(ao.getString("appname"))
                    || StringUtils.isEmpty(ao.getString("appurl")))
                continue;

            //企业应用
            if (ao.containsKey("mid")) {
                if (!StringUtils.equals(mid, ao.getString("mid")))
                    continue;
            }
            ao.remove("appsecret");
            ja.add(ao);
        }
        renderJSON(ja);

    }

    /**
     * 按企业eid查询企业开通应用，无eid的平台级公众号返回所有应用
     *
     * @throws Exception
     */
    public static void applistByEid() throws Exception {
        String eid = params.get("mid");
        JSONArray ja = open.getAppByEid(eid);
        renderJSON(ja);
    }

    /**
     * 按照appId查询appName，解决兼容性问题
     *
     * @throws Exception
     */
    public static void appNameByAid() throws Exception {
        String appId = params.get("appId");
        JSONObject ja;
        if(StringUtils.isEmpty(appId)){
             ja=null;
        }
        ja = open.getAppNameByAid(appId);
        renderJSON(ja);
    }

    /**
     * 应用授权
     * appid,consumer,eid
     *
     * @throws Exception
     */
    public static void appauth() throws Exception {
        Map<String, String> param = params.allSimple();
        param.remove("body");
        param.remove("action");
        JSONObject jo = new JSONObject();
        jo.putAll(param);
        if (StringUtils.isEmpty(jo.getString("appid")))
            ok();

        bo.setConsumer(param.get("appid"), jo.toJSONString());
        Logger.info("appauth=%s", jo);

        renderJSON(bo.getConsumer(param.get("appid")));

    }

    /**
     * 根据用户读取内容
     */
    public static void gethtml(String name, String pubId, String openId) throws Exception {
        if (StringUtils.isEmpty(openId)) {
            renderJSON(new Result("openId is null or empty!"));
        }
        // 判断用户是否订阅了该公共号
        String uid = open.getpersonidbyopenid(openId);
        Logger.info("openId: %s, uid: %s", openId, uid);
        if (StringUtils.isEmpty(uid)) {
            renderJSON(new Result("uid is null or empty!"));
        }
        // if (xt.subUserStatus(pubId, uid)) {
        if (manage.subUserStatus(pubId, uid)) {
            String html = bo.getHtml(name);
            if (StringUtils.isEmpty(html)) {
                html = FileCacheUtils.readFile(name);
                if(!StringUtils.isEmpty(html)){
                	bo.saveHtml(name, html);
                }
            }
            if (StringUtils.isEmpty(html)) {
                renderJSON(new Result("该信息发布超过30天，已过期失效！"));
            } else {
                renderJSON(new Result(true, html));
            }
        }
        renderJSON(new Result("该消息是机密信息，您无权浏览！"));
    }

    /**
     * 抢话筒应答
     */
    public static void getMic() throws Exception {

        GwHead msg;
        Gson gs = new Gson();

        params.getRootParamNode();
        String body = params.data.get("body")[0];
        Logger.info("getmic %s ", body);

        msg = gs.fromJson(body, GwHead.class);

        // if (msg.Payload.getString("EventKey").equals("getmic")) { //msg.MsgType.equals(GwHead.CLICK) &&

        if (bo.hget(PubAccountBO.MIC, msg.PubAccId) != null) {
            //间隔一分钟可抢
            if (Cache.get("getmic_" + msg.PubAccId) == null) {
                bo.delGroupUser(msg.PubAccId, Sess.ASS_SAY, null);
                bo.addGroupUser(msg.PubAccId, Sess.ASS_SAY, new String[]{msg.Uid});
                Cache.add("getmic_" + msg.PubAccId, "1", "1min");
                xt.sendMidMsg(msg.PubAccId, msg.Eid, msg.Uname + ":抢到话筒开始发言");
            } else {
                xt.sendTextMsg(msg.PubAccId, msg.Uid, "你没抢到话筒,请等一分钟", "");
            }
        } else {
            xt.sendTextMsg(msg.PubAccId, msg.Uid, "你不要抢了,已设置正式发言人", "");
        }

        // }else{
        //   Logger.error("getmic not");
        // }


        ok();

    }


    public static void person_service() throws Exception {
        WS.WSRequest url = WS.url(gw + "/person_service?" + params.urlEncode());
        Logger.info(url.url);
        url.timeout("30s");
        try {
            PubUtils.gwtoken(url);
            WS.HttpResponse res = url.get();
            if (res.getStatus() != 200) {
                Logger.error("media api error. code %s", res.getStatus());
            }
            Logger.info("api.media  url=%s", url.url);

            renderJSON(res.getString());
        } catch (Exception e) {
            Logger.error("api.media error %s.  url=%s,val=%s", e.getMessage(), url.url, url.parameters);
        }
    }

    public static void media_download() throws Exception {
        WS.WSRequest url = WS.url(gw + "/media_download?" + params.urlEncode());
        Logger.info(url.url);
        url.timeout("30s");
        try {
            PubUtils.gwtoken(url);
            WS.HttpResponse res = url.get();
            if (res.getStatus() != 200) {
                Logger.error("media api error. code %s", res.getStatus());
            }

            //组装文件名称和长度

            String content = res.getHeader("Content-Disposition");
            Logger.info("api.media  url=%s content=%s", url.url, content);
            if (!StringUtils.isEmpty(content)) {
                String len = res.getHeader("Content-Length");
                renderBinary(res.getStream(), StringUtils.substringAfterLast(content, "="), Long.parseLong(len));
            } else
                renderJSON(res.getString());

        } catch (Exception e) {
            Logger.error("api.media error %s.  url=%s,val=%s", e.getMessage(), url.url, url.parameters);
        }
    }

    public static void media_upload(File media) throws Exception {
        if (media == null) {
            errorText(5000, "必须传入上传文件media");
        }
        Logger.info("media upload file=%s", media.getName());
        WS.WSRequest url = WS.url(gw + "/media_upload?" + params.urlEncode());
        WS.FileParam mf = new WS.FileParam(media, "media");
        url.files(mf);
        url.timeout("30s");
        try {
            PubUtils.gwtoken(url);
            WS.HttpResponse res = url.post();
            if (res.getStatus() != 200) {
                Logger.error("media api error. code %s", res.getStatus());
            }
            Logger.info("api.media  url=%s", url.url);

            renderJSON(res.getString());
        } catch (Exception e) {
            Logger.error("api.media error %s.  url=%s,val=%s", e.getMessage(), url.url, url.parameters);
        }

    }

    @Before(only = {"person_service", "media_download", "media_upload"})
    public static void checktoken() throws Exception {
        Logger.info("media =%s", params.urlEncode());

        String from = params.get("from");
        if (StringUtils.isEmpty(from))
            errorText(5000, "必须传入参数from={pubid,no,nonce,time,pubtoken}");

        JSONObject param = JSON.parseObject(from);
        String pubid = param.getString("pub"), no = param.getString("no"), time = param.getString("time"),
                nonce = param.getString("nonce"), pubtoken = param.getString("pubtoken");

        // 验证公司签名
        if (pubid == null || no == null || nonce == null
                || time == null || pubtoken == null) {
            errorText(5000, "必须传入参数from={pub,no,nonce,time,pubtoken}", from);
        }

        PubUtils.debug(pubid, "check token %s", params.toString());

        PubPO pub = bo.findPub(pubid);
        if (pub == null || pub.state != 2) {

            errorText(5001, "公共号不存在或未审核！", pubid);
        }
        //公共号密钥验证
        if (StringUtils.isEmpty(pub.pubkey))
            errorText(5004, "公共号未设置密钥！", pubid);

        String ptoken = MD5Util.sha(pub.pubkey, no, pubid, time, nonce);
        if (!ptoken.equalsIgnoreCase(pubtoken)) {
            errorText(5004, "公共号密钥验证失败！", " pub %s sha  %s # %s", pubid, pubtoken, ptoken);
        }

    }

    static void errorText(int status, String tips) {
        String ip = getIP();
        if (status < 6000)
            bo.zincrBy(PubAccountBO.IP, ip, 1);
        Logger.error(ip + ":" + status + " " + tips);
        errlog.error(ip + ":" + status + " " + tips);

        throw new RenderErrorText(status, tips);
    }

    static void errorText(int status, String tips, CharSequence pattern, Object... args) {

        String msg = pattern == null ? "" : String.format(pattern.toString(),
                args);
        String ip = getIP();
//      Double lip = 0.00;
        if (status < 6000)
            bo.zincrBy(PubAccountBO.IP, ip, 1);
        errlog.error(ip + ":" + status + " " + tips + "--" + msg);
        Logger.error(ip + ":" + status + " " + tips);
        throw new RenderErrorText(status, tips);
    }

    @Before
    public static void ddos() throws Exception {
        String ip = getIP();
        Double lip = bo.zscore(PubAccountBO.IP, ip);
        if (lip < -1) {
            forbidden(FB + ip);
        }

    }

    static String getIP() {
        String ip = request.remoteAddress;
        return StringUtils.substringBefore(ip, ",");
    }

    public static void open1(String f, String p) throws Exception {
        bo.add1("htmlopencount", f);
        bo.add1(PubAccountBO.PUB_MESSAGEOPEN, p);
    }
    public static void open2(String f, String p,String personId) throws Exception {
    	Logger.info("f: %s, p: %s, personId: %s ", f,p,personId);
    	bo.saveHtmlUVCount("htmlopenuvcount",f, personId);
    }

    public static void opencount(String f) throws Exception {
        renderText("文件打开次数:" + bo.add0("htmlopencount", f));
    }
    public static void openuvcount(String f) throws Exception {
//    	int fcount = 0;
//    	String fct1 = bo.getFUVCount(f+"_uvcount");
//    	if(StringUtils.isEmpty(fct1)){
//    		fcount = bo.getUVPersonIdSize(f).intValue();
//    		bo.saveFUVCount(f+"_uvcount", String.valueOf(fcount));
//    	}else{
//    		fcount = Integer.valueOf(fct1)+bo.getUVPersonIdSize(f).intValue();
//    		bo.saveFUVCount(f+"_uvcount", String.valueOf(fcount));
//    	}
        renderText("文件被" + bo.add0("htmlopenuvcount", f)+"人打开过!");
    }
    
    static String NEWS = "XT-10002";

    //我的广播到部门
    public static void newsSend(String ticket, String title, String logo,
                                String text, String zip, String orgid) throws Exception {

         Logger.info("newsSend=%s", params.allSimple());
        if (StringUtils.isEmpty(ticket)) {
            errorText(400, "用户超时,请重新登录打开广播");
        }
        if (StringUtils.isEmpty(title) || StringUtils.isEmpty(orgid)) {
            errorText(400, "广播缺少标题或部门!");
        }

        Map<String, String> context = Cache.get("openauth_context_"
                + ticket, Map.class);
        if (context == null) {
            errorText(400, "用户超时,请重新登录打开广播");
        }
        String eid = context.get("eid");
//      String uid = context.get("xtid");

        //图文消息
        MessageHead head = new MessageHead();
        head.msgType = 6;
        head.model = 1;
        head.list=new ArrayList<MessageItem>(){};
        MessageItem mitem = new MessageItem();
        head.list.add(mitem);

        head.text = mitem.title = title;
        mitem.date = "播客:"+context.get("username");

        if (!StringUtils.isEmpty(zip)) {
            mitem.url = PubUtils.saveHtml(NEWS, zip, true);
        }
        if (StringUtils.isEmpty(text)) {
            if (mitem.url !=null)
                mitem.text = "点击播放...";
            else
                mitem.text = "见标题...";
        }else{
            mitem.text = text;
        }
        if (StringUtils.startsWithIgnoreCase(logo, "http")) {
            mitem.name = logo;
            head.model = 2;
        }

        String msg = new Gson().toJson(head);
        String msgid = xt.saveMessage(NEWS, 6, head.text, msg, 0);
        if (StringUtils.isEmpty(msgid)) {
            errorText(400,"保存消息失败,请重新打开广播");
        }
        
        SendMsgService.sendPubMsg2Some(NEWS, eid, msgid, PubUtils.toList(orgid), true);
        ok();
    }


    //我的广播图片
    public static void newsPic(String ticket, String name, String pic) throws Exception {
        if (StringUtils.isEmpty(ticket)) {
            errorText(400, "超时,请重新登录打开广播");
        }
        if (StringUtils.isEmpty(name) || StringUtils.isEmpty(pic)) {
            errorText(400, "缺少图片名称或数据!");
        }

        Map<String, String> context = Cache.get("openauth_context_"
                + ticket, Map.class);
        if (context == null) {
            errorText(400, "超时,请重新登录打开广播");
        }
        String picpath = PubUtils.saveFile("", name, pic);
        if (StringUtils.startsWithIgnoreCase(picpath, "http") &&
                StringUtils.endsWithIgnoreCase(picpath, name))
            renderText(picpath);
        else
            errorText(400, "图片上传失败!");
    }
    
    /**
     * XT-10000的发言人 回复云之家团队消息
     * 
     * @param from 发送者(回复者)personId
     * @param to 接受者personId 
     * @param msg 文本消息
     */
    public static void msgsend1000(String from, String to, String msg) {
    	try {
    		ValidateEmpty2Info.validate(from, "from is null or empty!");
        	ValidateEmpty2Info.validate(to, "to is null or empty!");
        	ValidateEmpty2Info.validate(msg, "msg is null or empty!");
			String sourceMsgId = msgService.reply2Sb("XT-10000", from, to, msg);
			renderJSON(new Result2(true, new JSONObject("sourceMsgId", sourceMsgId)));
		} catch (PubException e) {
			renderJSON(new Result2(e.getMessage()));
		} catch (Exception e) {
			renderJSON(new Result2(e.getMessage(), 500));
		}
    	
    }
    
    public static void pubmsgmonitor() throws Exception {
		GwHead msg;
		Gson gs = new Gson();
		params.getRootParamNode();
		String body = params.data.get("body")[0];
		Logger.info("monitor  %s ", body);
		msg = gs.fromJson(body, GwHead.class);
		String uid = msg.Uid;
		String app = msg.PubAccId;
		Set<String> users = manage.findSsbUser(app, 0, -1);
		if (users.isEmpty() || !users.contains(uid)) {
			xt.sendTextMsg(app, uid, "你不是该公共号的订阅用户！", "");
			return;
		}
		String status = bo.getPubMsgSendStatus("XT-10000");
		if (StringUtils.isNotEmpty(status)) {
			xt.sendTextMsg(app, uid, status, "");
		}
	}
    
    public static void innerAppInfo(String appId) {
    	if (StringUtils.isEmpty(appId)) {
			renderJSON(new Result2("appId is null or empty!"));
		}
    	
    	JSONObject app = bo.getapp(appId);
    	if (null == app) {
    		renderJSON(new Result2("轻应用不存在!"));
		}
    	
    	renderJSON(new Result2(true, app));
    }
    
    public static void getPubaccInfoByEid(){
    	String mid = params.get("mid");
        String pubName = params.get("pubName");
        try{
        	if (StringUtils.isEmpty(mid) || StringUtils.isEmpty(pubName)) {
                errorText(500, "必须传入参数:mid,pubName");
            }
        	Logger.info("getPubaccInfoByEid传入的参数 mid is %s,pubName is", mid, pubName);
        	JSONObject result = appSubService.getPubaccInfoByEid(mid,pubName);
        	Logger.info("返回的结果:"+result);
        	renderJSON(result);
        } catch (PubException e) {
 			String detail = e.getDetail();
 			if (StringUtils.isEmpty(detail)) {
 				errorText(e.getStatusCode(), e.getMessage());
 			} else {
 				errorText(e.getStatusCode(), e.getMessage(), e.getDetail());
 			}
 		} catch (Exception e) {
 			Logger.error(e.getMessage());
 			errorText(500, e.getMessage());
 		}
    }
    
    public static void usersadd(String user,String pid)throws Exception{
//    	Logger.info("pid is %s", pids.toString());
//    	bo.delGroupUser(pid, Sess.ASS_SAY, null);
    	String[] users = user.split(",");
    	String[] pids =pid.split(",");
    	if(pids.length>0){
    		for(String pid1:pids){
    			bo.addGroupUser(pid1, Sess.ASS_SAY, users);
    	        for (String u : users) {
    	        	Logger.info("user is %s", u);
    	            bo.addAssUser(u, pid1);
    	            xt.sendTextMsg(pid1, u, "你已设置为发言人!", "");
    	        }
        	}
    	}
        ok();
    }
    
    public static  void usersdel(String user,String pid)throws Exception{
    	String[] users = user.split(",");
    	String[] pids =pid.split(",");
    	if(pids.length>0){
    		for(String pid1:pids){
    			bo.delGroupUser(pid1, Sess.ASS_SAY, users);
    			for (String u : users) {
    				bo.delAssUser(u, pid1);
    				xt.sendTextMsg(pid1, u, "你的发言人权限已被撤销，详细请咨询管理员。", "");
    			}
    			PubPO pub = bo.findPub(pid1);
    			if (null != pub) {
    				String mid = StringUtils.isEmpty(pub.mid)?"all":pub.mid;
    				xt.notifyChange(pid1, mid);
    			}	
    		}
    	}
    }
    
    public static void setPubReplyType(String pid,int replyType){
    	PubPO pub = bo.findPub(pid);
    	if(replyType==1){
    		pub.replyType = true;
    	}
    	bo.setBean(bo.genSys(PubAccountBO.PUBS,pub.pid), pub);
    	String p = new Gson().toJson(pub);
    	Logger.info("setPubReplyType p is %s", p);
    	bo.hset("pubid", pid, p);
    }
    
    public static void setPubSourceType(String pid,String sourceType){
    	PubPO pub = bo.findPub(pid);
    	if(StringUtils.isNotBlank(sourceType)){
    		pub.sourceType = sourceType;
    	}
    	bo.setBean(bo.genSys(PubAccountBO.PUBS,pub.pid), pub);
    	bo.dopub(pub);
    	String p = new Gson().toJson(pub);
    	bo.hset("pubid", pid, p);
    	Logger.info("setPubSourceType p is %s", p);
    }
    
    public static void testShowUpgraded(String[] pid)throws Exception{
//    	Logger.info("testShowUpgraded pid is %s", null);
    	List<String> strs = Arrays.asList(pid);
    	for(String str :strs){
    		Logger.info("str is %s", str);
    	}
//    	pubRecMsgService.showUpgraded(pid);
    	ok();
    }
    public static void getEidsBySourceType(String sourceType){
    	JSONObject result = new JSONObject();
    	try {
    		Set<String> sets = xt.getEidsByCategoryId(sourceType);
        	result.put("data", JSONObject.toJSON(sets));
        	result.put("success", true);
    	}catch(Exception e){
    		result.put("success",false);
    		result.put("data", null);
    	}
    	renderJSON(result);
    }
//    public static int getTimeout() {
//    	return timeout.get();
//    }
//    
//    public void setTimeout(int timeout) {
//    	this.timeout.set(timeout);
//    }
    //新增获取公共号头像接口
//    public static void getPubUrl() {
//    	try {
//    		params.getRootParamNode();
//        	String body = params.data.get("body")[0];
//        	String photoUrl = msgService.getPubUrlByApi(body);
//        	JSONObject resultObj = new JSONObject();
//        	resultObj.put("photoUrl", photoUrl);
//        	renderJSON(resultObj);
//		} catch (PubException e) {
//			String detail = e.getDetail();
//			if (StringUtils.isEmpty(detail)) {
//				errorText(e.getStatusCode(), e.getMessage());
//			} else {
//				errorText(e.getStatusCode(), e.getMessage(), e.getDetail());
//			}
//		} catch (Exception e) {
//			Logger.error(e.getMessage());
//			errorText(500, e.getMessage());
//		}
//    }
}
