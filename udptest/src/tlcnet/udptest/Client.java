package tlcnet.udptest;

import java.io.*;
import java.net.*;
import java.util.Arrays;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

public class Client
{
	private static final int RX_BUFSIZE = 2048; // Exceeding data will be discarded: note that such a datagram would be fragmented by IP
	private static final short ACK_TIMEOUT = 2000;
	private static final int DEF_CHANNEL_PORT = 65432; // known by client and server
	static final int DEF_CLIENT_PORT = 65431;
	static final int BLOCK_SIZE = 512;
	static final int WINDOW_SIZE = 10;



	public static void main(String args[]) throws IOException
	{
		int channelPort = DEF_CHANNEL_PORT;
		int dstPort = Server.DEF_SERVER_PORT;
		InetAddress channelAddr;
		InetAddress dstAddr;
		long startTransferTime = 0;
		boolean firstPacket = true;
		DatagramSocket socket = null;

		if (args.length != 3) {
			System.out.println("Usage: java Client <dest address> <channel address> <local file>"); 
			return;
		}

		// ---- Create socket ----
		try {
			socket = new DatagramSocket(DEF_CLIENT_PORT);
		}
		catch (SocketException e) {
			System.err.println("Error creating datagram socket:\n" + e);
			return;
		}



		try {
			dstAddr = InetAddress.getByName(args[0]);
			channelAddr = InetAddress.getByName(args[1]);
		}
		catch (UnknownHostException e) {
			System.err.println(e);
			socket.close();	return;
		}

		RandomAccessFile theFile = null;
		try	{
			String fileName = args[2];
			theFile = new RandomAccessFile(fileName, "r");	//creating a file reader
		}
		catch(FileNotFoundException e)	{
			System.out.println("Error: file not found");
			socket.close();
			return;
		}
		FileChannel inChannel = theFile.getChannel();
		ByteBuffer chunkContainer = ByteBuffer.allocate(WINDOW_SIZE*BLOCK_SIZE);


		
		// * * * * * * * * * * * * * *//
		// * *  DATA TRANSFER LOOP * *//
		// * * * * * * * * * * * * * *//

		
		
		int sn = 1; int begin = 0; int end = (WINDOW_SIZE - 1)*BLOCK_SIZE; //begin and end keep track of the frames we are sending
		
		String ack = "";
		boolean first_pck = true;		//start of transmission
		byte first[] = new byte[WINDOW_SIZE*BLOCK_SIZE]; //TODO: FIX TIMER
		byte sec[] = new byte[WINDOW_SIZE*BLOCK_SIZE]; 	//Two arrays of bytes that help sending data
		boolean mustSend = true;						//To stop the loop
		int segmentCounter = 1;							//To know how many "windows" we sent
		int pcksInFirst = WINDOW_SIZE;					//How many pcks of the window are in the first byte array
		boolean slide = false;
		int lastSn = 0;
		int lastSnInWindow = 0;
		
		while(mustSend)	{
			int offset = 0; //offset in the bytebuffer
			if(first_pck)	{
				first_pck = false;		//The beginning comes just once :P
				fill(first, chunkContainer, inChannel);	//Check it out below
				fill(sec, chunkContainer, inChannel);
				if(sec.length == 0 && first.length != 0 && first.length < WINDOW_SIZE*BLOCK_SIZE)	{
					if(first.length%BLOCK_SIZE != 0)
						end = (first.length/BLOCK_SIZE)*BLOCK_SIZE;
					else
						end = first.length - BLOCK_SIZE;		//This is to deal with very small pcks
					lastSn = end + 1;
				}
				else if(sec.length == 0 && first.length == 0)	{
					System.out.println("Empty packet. Shutting down.");	//TODO: fix 0pck transmission
					socket.close();
					theFile.close();
					return;
				}
				//Sending first pck-window
				lastSnInWindow = WINDOW_SIZE;
				for(int i = begin; i <= end; i += BLOCK_SIZE)	{
					int pck_size = BLOCK_SIZE;
					if(i == end && first.length%BLOCK_SIZE != 0)
						pck_size = first.length%BLOCK_SIZE;
					byte[] temp = new byte[pck_size]; 
					System.arraycopy(first, i, temp, 0, pck_size);
					send(lastSnInWindow, lastSn, sn, temp, dstAddr, dstPort, channelAddr, channelPort, socket, theFile);
					sn++;
				}
			}
			
			
			else	{
				//Scorro la stringa ack e trovo offset. poi rimando i pacchetti non ricevuti
				boolean firstDroppedPck = false;
				for(int i = 0; i < ack.length(); i++)	{
					if(ack.substring(i,i+1).equals("0"))	{
						if(!firstDroppedPck && lastSn == 0)	{
							firstDroppedPck = true;
							offset = i;
						}
						lastSnInWindow = sn - 1 + offset;
						//Re-send the dropped pcks
						if(i < pcksInFirst)	{		//Reading from first byte array
							int pck_size = BLOCK_SIZE;
							if((i*BLOCK_SIZE == end) && first.length%BLOCK_SIZE != 0)	{
								pck_size = first.length%BLOCK_SIZE;
							}
							byte[] temp = new byte[pck_size]; 
							System.arraycopy(first, begin + i*BLOCK_SIZE, temp, 0, pck_size);
							send(lastSnInWindow, lastSn, sn - WINDOW_SIZE + i, temp, dstAddr, dstPort, channelAddr, channelPort, socket, theFile);
						}
						else	{
							int index = i - pcksInFirst;
							int pck_size = BLOCK_SIZE;
							if((index*BLOCK_SIZE == end) && sec.length%BLOCK_SIZE != 0)	{
								pck_size = sec.length%BLOCK_SIZE;
							}
							byte[] temp = new byte[pck_size]; 
							System.arraycopy(sec, index*BLOCK_SIZE, temp, 0, pck_size);//ERROR: TODO: check lastSNInW
							send(lastSnInWindow, lastSn, sn - WINDOW_SIZE + i, temp, dstAddr, dstPort, channelAddr, channelPort, socket, theFile);
						}
					}
				}
				//Slide the window
				if(offset!=0 && lastSn == 0)	{
					if((WINDOW_SIZE - pcksInFirst + offset)*BLOCK_SIZE < sec.length)	{
						begin += offset*BLOCK_SIZE;
						end = (WINDOW_SIZE - pcksInFirst + offset - 1)*BLOCK_SIZE;
						pcksInFirst = (first.length - begin)/BLOCK_SIZE;
						slide = true;
					}
					else if(sec.length!=0 && sec.length < BLOCK_SIZE*WINDOW_SIZE)	{
						if((sec.length - end)%BLOCK_SIZE != 0)
							offset = (sec.length - end)/BLOCK_SIZE;
						else
							offset = (sec.length - end)/BLOCK_SIZE - 1;
						begin += offset*BLOCK_SIZE;
						end = (WINDOW_SIZE - pcksInFirst + offset)*BLOCK_SIZE;
						pcksInFirst = first.length - begin;
						//This is the last window
						lastSn = sn - 1 + offset;
						if(offset != 0)		//Offset may have changed
							slide = true;
					}
					else if(sec.length == BLOCK_SIZE*WINDOW_SIZE)	{
						first = sec;
						fill(sec, chunkContainer, inChannel);
						if( (offset*BLOCK_SIZE) - (first.length - end - BLOCK_SIZE) <= sec.length)	{
							begin = (offset - pcksInFirst)*BLOCK_SIZE;
							if(begin == 0)
								end = (WINDOW_SIZE -1)*BLOCK_SIZE;
							else	
								end = begin - BLOCK_SIZE;
							pcksInFirst = first.length - begin;
							slide = true;
						}
						else if(sec.length != 0 && sec.length < BLOCK_SIZE*WINDOW_SIZE)	{
							if(sec.length%BLOCK_SIZE != 0)	{
								end = sec.length/BLOCK_SIZE;
								begin = end + BLOCK_SIZE;
								offset = pcksInFirst*BLOCK_SIZE + begin;
							}
							else	{
								end = sec.length/BLOCK_SIZE - 1;
								begin = end + BLOCK_SIZE;
								offset = pcksInFirst*BLOCK_SIZE + begin;
							}
							lastSn = sn - 1 + offset;
							pcksInFirst = first.length - begin;
							slide = true;
						}
						else	{	//sec.length == 0
							end = (WINDOW_SIZE - 1)*BLOCK_SIZE;
							begin = 0;
							offset = pcksInFirst*BLOCK_SIZE + begin;
							lastSn = sn - 1 + offset;
							if(offset != 0)
								slide = true;
						}
					}
				}
			}
			//Send new pcks
			if(slide)	{
				for(int i = WINDOW_SIZE - offset; i < WINDOW_SIZE; i++)	{		//TODO: check
					if(pcksInFirst - i > 0)	{
						int pck_size = BLOCK_SIZE;
						if((i == WINDOW_SIZE - 1) && first.length%BLOCK_SIZE != 0)	{	//TODO:useless?
							pck_size = first.length%BLOCK_SIZE;
						}
						byte[] temp = new byte[pck_size]; 
						System.arraycopy(first, begin + i*BLOCK_SIZE, temp, 0, pck_size);
						send(lastSnInWindow, lastSn, sn, temp, dstAddr, dstPort, channelAddr, channelPort, socket, theFile);
						sn++;
					}
					else	{
						int index = i - pcksInFirst;
						int pck_size = BLOCK_SIZE;
						if((index*BLOCK_SIZE == end) && sec.length%BLOCK_SIZE != 0)	{
							pck_size = sec.length%BLOCK_SIZE;
						}
						byte[] temp = new byte[pck_size]; 
						System.arraycopy(sec, index*BLOCK_SIZE, temp, 0, pck_size);
						send(lastSnInWindow, lastSn, sn, temp, dstAddr, dstPort, channelAddr, channelPort, socket, theFile);
						sn++;
					}
				}
				slide = false;
			}
			
			//Receive ack
			byte[] recvBuf = new byte[RX_BUFSIZE];
			DatagramPacket recvPkt = new DatagramPacket(recvBuf, recvBuf.length);
			socket.setSoTimeout(ACK_TIMEOUT);
			try{
				socket.receive(recvPkt);
			}
			catch (SocketTimeoutException e) {
				System.out.println("timeout for ack n. " + segmentCounter + " expired: resending");
				ack = Utils.AckToBinaryString(0, WINDOW_SIZE);	//Ack is a string of zeros
				continue; 
			}
			catch(IOException e) {
				System.err.println("I/O error while receiving datagram:\n" + e);
				socket.close(); System.exit(-1);
			}
			
			//Process received ack
			byte[] recvData = Arrays.copyOf(recvPkt.getData(), recvPkt.getLength()); // Payload of recv UDP datagram
			UTPpacket recvUTPpkt = new UTPpacket(recvData);		// Parse UDP payload
			if (recvUTPpkt.function == UTPpacket.FUNCT_ACKDATA)	{
				ack = Utils.AckToBinaryString(recvUTPpkt.sn, WINDOW_SIZE);
				System.out.println("ACK n. " + segmentCounter + ": " + ack);
			if(lastSn != 0 && lastSnInWindow != lastSn)	{
				int digits = (lastSnInWindow - lastSn);
				String ones = Utils.AckToBinaryString((int) Math.pow(2, digits) - 1, digits);
				ack = ones + ack.substring(0, WINDOW_SIZE - digits);
			}
			if(lastSn != 0 && recvUTPpkt.sn == (int) Math.pow(2, WINDOW_SIZE) - 1)		//It means that everything was acked
				mustSend = false;
			}
		}
	}
	
	
	//SOME AUXILIARY METHODS
	
	static void fill(byte[] one, ByteBuffer container, FileChannel channel)	throws IOException {
		long howManyBytes = channel.read(container);
		if(howManyBytes == BLOCK_SIZE*WINDOW_SIZE)	{
			container.flip();	//Important! Otherwise .remaining() method gives 0
			container.get(one, 0, one.length);
			container.clear();
		}
		else if(howManyBytes > 0)	{
			container.flip();	//Important! Otherwise .remaining() method gives 0
			one = new byte[container.remaining()];
			container.get(one, 0, one.length);
			container.clear();
		}
		else one = new byte[0];
	}
	
	static void send(int lastSnInWindow, int lastSn, int sn, byte[] temp, InetAddress dstAddr, int dstPort, InetAddress channelAddr, int channelPort, DatagramSocket socket, RandomAccessFile theFile) 
	throws IOException {
		UTPpacket sendUTPpkt = new UTPpacket();
		sendUTPpkt.sn = sn;
		sendUTPpkt.dstAddr = dstAddr;
		sendUTPpkt.dstPort = (short)dstPort;
		sendUTPpkt.lastSnInWindow = lastSnInWindow;	//This is just a reference for receiver
		if(sn == lastSn)
			sendUTPpkt.function = (byte) UTPpacket.FUNCT_FIN;
		else
			sendUTPpkt.function = (byte) UTPpacket.FUNCT_DATA;
		sendUTPpkt.payl = temp;	
		byte[] sendData = sendUTPpkt.getRawData();
		DatagramPacket sndPkt = new DatagramPacket(sendData, sendData.length, channelAddr, channelPort);
		try {
			System.out.println("\n------\nSending SN=" + sn);
			socket.send(sndPkt);
		}
		catch(IOException e) {
			System.err.println("I/O error while sending datagram");
			socket.close(); theFile.close();
			System.exit(-1);
		}
	}
}