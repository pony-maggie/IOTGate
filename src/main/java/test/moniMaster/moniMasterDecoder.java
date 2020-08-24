package test.moniMaster;

import gate.base.constant.ConstantValue;
import gate.base.domain.ChannelData;
import gate.base.domain.SocketData;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;
/**
 * 解码器，模拟前置解码器
 * 
 * @author BriansPC
 *
 */
public class moniMasterDecoder  extends ByteToMessageDecoder{

	@Override
	protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
		
		//解码网关头 获取终端ip
		ChannelData channelData = decodeGateHeader(in);
		if(channelData != null){
			out.add(channelData);
			
		}
		
		

	}
	private ChannelData decodeGateHeader(ByteBuf in){
		//这里应该多加一些判断，防止流量攻击
		if(in.readableBytes()>31){
			StringBuilder clientIpAddress ;
			int beginReader;
			
			while (true) {
				beginReader = in.readerIndex();//可以读的开始位置
				int gateHeader = in.readByte() & 0xFF;
				if(gateHeader == ConstantValue.GATE_HEAD_DATA){
					int socketDataLen =  readLenArea(in);//in.readShortLE();//4字节长度
					if(in.readableBytes() >= (socketDataLen+25) ){
						in.readerIndex(beginReader);//带入参表示setter方法
						SocketData data = new SocketData(in.readBytes(socketDataLen+30));
						ChannelData channelData =  new ChannelData(data);
						
						
						return channelData;
					}else{
						in.readerIndex(beginReader);
						break;
					}
				}else{
					if (in.readableBytes() <= 31) {
						
						return null;
					}
					continue ;
				}
			}
		}
		
		return null;
	}
	
	/**
	 * @param in byteBuf
	 * @return
	 */
	private int readLenArea(ByteBuf in){
		int count = (in.readByte() & 0xFF) + ((in.readByte() & 0xFF) << 8 ) + ((in.readByte() & 0xFF) << 16  ) + ((in.readByte() & 0xFF) << 24 );
		return count;
	}

}
