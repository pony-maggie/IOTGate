package gate;

import java.io.BufferedReader;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.PosixParser;

import gate.base.cache.ClientChannelCache;
import gate.base.cache.ProtocalStrategyCache;
import gate.base.chachequeue.CacheQueue;
import gate.client.Client2Master;
import gate.cluster.ZKFramework;
import gate.concurrent.ThreadFactoryImpl;
import gate.rpc.rpcProcessor.RPCProcessor;
import gate.rpc.rpcProcessor.RPCProcessorImpl;
import gate.server.Server4Terminal;
import gate.threadWorkers.MClient2Tmnl;
import gate.threadWorkers.TServer2MClient;
import gate.util.BannerUtil;
import gate.util.CommonUtil;
/**
 * 入口
 * @Description: 
 * @author  yangcheng
 * @date:   2019年3月18日
 */
public class Entrance {
	
	public static CommandLine commandLine = null;
	public static int gatePort = 9811;
	public static String zkAddr = null;
	public static List<String> masterAddrs = new ArrayList<>(1);
	public static CountDownLatch locks = new CountDownLatch(1);
	private static RPCProcessor processor = new RPCProcessorImpl();
	private static String[] protocolType;
	/**
	 * 
	 * @param args
	 */
	public static void main(String[] args) {
		
		boolean isCluster = suitCommonLine(args);
		BannerUtil.info();
		/**
		 * 这个设置源自一个bug
		 * https://netty.io/news/2012/09/06/3-5-7-final.html
		 *
		 * https://www.xianglong.work/blog/17
		 */
		System.setProperty("org.jboss.netty.epollBugWorkaround", "true");
		initEnvriment();
		if(isCluster){
			try {
				locks.await();
			} catch (InterruptedException e1) {
				e1.printStackTrace();
			}
		}else{
			startCli();
		}
		startSev( protocolType);	
		try {
			processor.exportService();
		} catch (Exception e) {
			e.printStackTrace();
			System.err.println("rpc服务发布失败...............");
		}
		/**
		 * kill pid时 该方法会自动执行
		 */
		addHook();
	}

	
	/**
	 * 命令行
	 */
	public static boolean suitCommonLine(String[] args){
		
		commandLine =
				 CommonUtil.parseCmdLine("iotGateServer", args, CommonUtil.buildCommandlineOptions(new Options()),
                    new PosixParser());
        if (null == commandLine) {
            System.exit(-1);
        }
		boolean isCluster = false;
        //-c 标识集群模式，-z标识后面知道zk的地址
        if(commandLine.hasOption("c") && commandLine.hasOption("z")){
        	isCluster = true;
        	zkAddr = commandLine.getOptionValue("z");
        	new ZKFramework().start(zkAddr);
        }else if (commandLine.hasOption("m")) {
        	//-m 表示前置地址
        	String[] vals =  commandLine.getOptionValue("m").split("\\,");
        	for (String string : vals) {
        		masterAddrs.add(string);
			}
        }else{
        	System.err.println("启动参数有误，请重新启动");
        	System.exit(-1);
        }
        String confFile = commandLine.getOptionValue("f");//配置文件
        protocolType = getProtocolType(confFile);
        
        CommonUtil.gateNum = Integer.parseInt(commandLine.getOptionValue("n"));//网关编号
        System.out.println(String.format("网关编号为：%s", CommonUtil.gateNum));
        if(commandLine.hasOption("p")){
        	gatePort = Integer.parseInt(commandLine.getOptionValue("p"));//可选参数，端口
   	 	}
        return isCluster;
	}
	/**
	 * 环境初始化
	 */
	public static  void initEnvriment(){
		
		
		//初始化数据中转线程
		try {
			new TServer2MClient(CacheQueue.up2MasterQueue,1).start();//网关到前置(master)
			new MClient2Tmnl(CacheQueue.down2TmnlQueue, 1).start();//前置到终端
		} catch (Exception e) {
			System.err.println("数据中转线程启动失败");
			e.printStackTrace();
			System.exit(-1);
		};
		
	}
	/**
	 * JVM的关闭钩子--JVM正常关闭才会执行
	 * 感觉似乎没有必要加这个钩子啊，程序都退出了，map肯定都已经清空了
	 */
	public static void addHook(){
		Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
			
			public void run() {
				//清空缓存信息
				System.out.println("网关正常关闭前执行  清空所有缓存信息...............................");
				ClientChannelCache.clearAll();
				CacheQueue.clearIpCountRelationCache();
				CacheQueue.clearMasterChannelCache();
			}
		}));
	}

	/**
	 * protocolType=1,0,-1,1,2,1,1,9811,60;
	 * @param filePath
	 * @return
	 */
	@SuppressWarnings("resource")
	public static String[] getProtocolType(String filePath){
		File conf=  new File(filePath);
		System.setProperty("BasicDir",conf.getParent() );
		BufferedReader bufferedReader =null;
        try {
        	bufferedReader = new BufferedReader(new FileReader(conf));
        	String str;
        	while((str = bufferedReader.readLine()) != null){
        		if(str.startsWith("protocolType")){
        			return str.split("\\=")[1].split(";");//多个规约采用;分隔
        		}
            }
        	
		} catch (FileNotFoundException e) {
			e.printStackTrace();
			System.err.println("配置文件加载失败");
        	System.exit(-1);
		} catch (IOException e) {
			e.printStackTrace();
		}finally {
			try {
				bufferedReader.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
        return null;
        
	}
	
	public static void startCli(){
		//启动与前置对接的客户端  因为是阻塞运行 需要开线程启动
		
		for(int i = 0 ; i < masterAddrs.size() ; i++){
			String addr = masterAddrs.get(i);
			new Thread(new Runnable() {
				public void run() {
					try {
						Client2Master client2Master = new Client2Master();
						//这里的8888写死了，和前置master监听的端口保持一致就行
						client2Master.bindAddress2Client(client2Master.configClient(addr,8888,true));
						
					} catch (Exception e) {
						e.printStackTrace();
						System.exit(-1);
					}
				}
			},"gate2masterThread_ip_"+addr).start();
		}
	}
	
	public static void startSev(String[] protocolType){
		for(int i = 0 ; i < protocolType.length ; i++){//多少个规约，就启动多少个线程
			//启动与终端对接的服务端  因为是阻塞运行 需要开线程启动---后续版本中会变动
			String pts =  protocolType[i];
			String pid = pts.split("\\,")[0];//pId
			
			new Thread(new Runnable() {
				public void run() {
					// TODO Auto-generated method stub
					
					String[] pt = pts.split("\\,");
					boolean isBigEndian = "0".equals(pt[1]) ? false : true;
					boolean isDataLenthIncludeLenthFieldLenth = "0".equals(pt[5]) ? false : true;//长度域的表示的长度是否包含长度域本身
					//原来最后一个字段标识的是心跳周期
					System.out.println(String.format("！！！网关开始提供规约类型为%s的终端连接服务，开启端口号为：%s，心跳周期为：%s S", Integer.parseInt(pt[0]),Integer.parseInt(pt[7]),Integer.parseInt(pt[8])));
					Server4Terminal server4Terminal = new Server4Terminal(pt[0],pt[7]);
					server4Terminal.bindAddress(server4Terminal.config(Integer.parseInt(pt[0]),isBigEndian,Integer.parseInt(pt[2]),
							Integer.parseInt(pt[3]),Integer.parseInt(pt[4]),isDataLenthIncludeLenthFieldLenth,Integer.parseInt(pt[6]),Integer.parseInt(pt[8])));//1, false, -1, 1, 2, true, 1
					
				}
			},"gate2tmnlThread_pid_"+pid).start();
			ProtocalStrategyCache.protocalStrategyCache.put(pid, pts);
		}		
	}
}
