package tlcnet.udptest;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;



/**
 * This is a runnable class that is useful if called for instance by a ScheduledThreadPoolExecutor. It comes
 * in handy when in need of sending a packet asynchronously, maybe after a delay. Moreover, this task can be
 * performed a number of times, with a specified delay among transmissions.
 */

public class AsyncRepeatedPacketSender implements Runnable
{
	private DatagramSocket dstSock;
	private DatagramPacket sendPkt;
	private int times;
	private int interDelay;

	public AsyncRepeatedPacketSender(DatagramSocket dstSock, DatagramPacket sendPkt) {
		this(dstSock, sendPkt, 1);
	}
	
	public AsyncRepeatedPacketSender(DatagramSocket dstSock, DatagramPacket sendPkt, int times, int interDelay) {
		super();
		this.dstSock = dstSock;
		this.sendPkt = sendPkt;
		this.times = times;
		this.interDelay = interDelay;
	}
	
	public AsyncRepeatedPacketSender(DatagramSocket dstSock, DatagramPacket sendPkt, int times) {
		this(dstSock, sendPkt, times, 0);
	}


	@Override
	public void run() {

		try {
			for (int i = 0; i < times; i++) {
				dstSock.send(sendPkt);
				if (interDelay > 0)
					Thread.sleep(interDelay);
			}
		}catch (InterruptedException e) {
			//e.printStackTrace();
		} catch(IOException e) {
			System.err.println("I/O error while sending datagram:\n" + e);
			dstSock.close(); System.exit(-1);
		}
	}
}