package tlcnet.udptest;

import java.io.*;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Arrays;

/* TODO: Gestire il caso in cui ricevo il fin ma mi mancano ancora alcuni pacchetti nel buffer
 */


public class Server {
	
	static final int DEF_CHANNEL_PORT = 65432;
	static final int DEF_SERVER_PORT = 65433;
	private static final short END_TIMEOUT = 20000;		//To stop waiting for pcks
	private static final int RX_BUFSIZE = 2048; // Exceeding data will be discarded: note that such a datagram would be fragmented by IP
	
	// When the write buffer exceeds this number of bytes, it is written on the output file
	private static final int WRITEBUF_THRESH = 20 * 1024;

	private static final int WINDOW_TIMEOUT = 1000; //To stop waiting the pck of the window and send the ack
	static final int BLOCK_SIZE = 512;					//I don't know if I can put this information here

	public static void main(String[] args) {

		int listenPort = DEF_SERVER_PORT;
		int channelPort = DEF_CHANNEL_PORT;
		int clientPort = Client.DEF_CLIENT_PORT;
		InetAddress clientAddr = null;
		String filename = null;
		FileOutputStream fileOutputStream = null;
		InetAddress channelAddr = null;

		// Check input parameters
		if (args.length != 2) {
			System.out.println("Usage: java Server <client address> <path to new file>");
			return;
		}

		try {

			// Get address of client from command line parameter
			clientAddr = InetAddress.getByName(args[0]);

			// Create output file and overwrite if it already exists
			filename = args[1];
			fileOutputStream = new FileOutputStream(filename, false);

		} catch (UnknownHostException e) {
			System.err.println(e); return;
		} catch (FileNotFoundException e) {
			System.err.println("Cannot create file!\n" + e);
		}

		// --- Create the socket ---
		DatagramSocket socket = null;
		try {
			socket = new DatagramSocket(listenPort);
			socket.setSoTimeout(END_TIMEOUT);
		}
		catch(SocketException e) {
			System.err.println("Error creating a socket bound to port " + listenPort);
			System.exit(-1);
		}



		// * * * * * * * * * * * * * *//
		// * *  DATA TRANSFER LOOP * *//
		// * * * * * * * * * * * * * *//


		// Create output stream to write received data. This is periodically emptied on the out file.
		ByteArrayOutputStream writeBuffer = new ByteArrayOutputStream();

		
		boolean new_window = true; 		//I use that to calculate window_size
		boolean notAllAcked = false;
		long lastSnWind = 0;
		long firstSnWind = 1;			//first sn in the current window
		long SN = 1;						//is useless
		int window_size = 60;
		byte[][] DataBuffer = new byte[window_size][BLOCK_SIZE];			//data buffer while receiving the window's packets 
		boolean[] Ack = new boolean[window_size];						//the ACK of the current window
		long startTransferTime = System.currentTimeMillis();
		long startTime = System.currentTimeMillis();
		boolean allTrue = true;
		boolean allFalse = false;
		int firstFalse = 0;
		long snFIN = 0;												//SN of the FIN pkt
		long snFINWind = 0;											
		int FinLength = 0;											//Length of the payload of the FIN pkt
		byte[] FinPayl = new byte[BLOCK_SIZE];						//Payload of the FIN pkt
		int n = 0;
		int countOld = 0;
		long oldSn = 0;
		boolean first = true;
		int lastFinSn = 0;											
		
		boolean gotFIN = false; //Needed to stop the cycle
		while(!gotFIN)
		{
			
			startTransferTime = System.currentTimeMillis();	
			
			//------Windoe cycle
			
			boolean WINFIN = false;	//Needed to stop the cycle
			while(!WINFIN && (System.currentTimeMillis() - startTransferTime) < (long)WINDOW_TIMEOUT)	{
			// ---- Receive packet ----

			byte[] recvBuf = new byte[RX_BUFSIZE];
			DatagramPacket recvPkt = new DatagramPacket(recvBuf, recvBuf.length);
			try{
				socket.receive(recvPkt);
			}
			catch (SocketTimeoutException e) {		
				System.out.println("Closing connection: FIN not received...");
				break;
			}
			catch(IOException e) {
				System.err.println("I/O error while receiving datagram:\n" + e);
				socket.close(); System.exit(-1);
			}




			// ---- Process received packet----

			byte[] recvData = Arrays.copyOf(recvPkt.getData(), recvPkt.getLength());  // payload of recv UDP packet
			UTPpacket recvUTPpkt = new UTPpacket(recvData);			// parse payload
			channelAddr = recvPkt.getAddress();			// get sender (=channel) address and port


			

			//--------Only the first packet of the window------- 
			
			if(new_window)	{
				
				
				firstSnWind = lastSnWind + 1;						//calculate the first SN of the new window
				lastSnWind = recvUTPpkt.lastSnInWindow;				//last SN of the new window
				startTransferTime = System.currentTimeMillis();			//start the timer
				DataBuffer = new byte [window_size][BLOCK_SIZE];   
				Ack = new boolean[window_size];
				countOld = 0;
				
				new_window = false;
			}	
			
			//------if some packets of the previous window are lost----------
			
			else if(notAllAcked){
					
				
				
				firstSnWind = firstSnWind + firstFalse;				//calculate the first SN of the new window			
				lastSnWind = firstSnWind + window_size - 1;			//last SN of the new window
				startTransferTime = System.currentTimeMillis();			//start the timer
				DataBuffer = Translation(DataBuffer, firstFalse, window_size, BLOCK_SIZE);//translate the buffer of the previous window
				Ack  = Translation(Ack, firstFalse, window_size);
				new_window = false;
				notAllAcked = false;
				countOld = 0;
				
				
				if (snFIN != 0)	{               //if the FIN is just arrived, update some indices
					snFINWind = snFIN- firstSnWind; 
					lastFinSn = recvUTPpkt.lastSnInWindow;
				}
				
				
			
			}
			
			
			//-----if received some pkt of previous windows, send again the ack ralated to that window----
			if (recvUTPpkt.lastSnInWindow < lastSnWind){
				countOld++;
				oldSn = recvUTPpkt.lastSnInWindow;
			}
			
			if (countOld > 2){
				boolean[] oldAck = new boolean[window_size];
				
				//--------------create oldAck-------------
				for(int i = 0; i < (lastSnWind - oldSn); i++)	{
					oldAck[i] = true;
				}
				
				for(int j = (int) (lastSnWind - oldSn); j < window_size; j++)	{
					oldAck[j] = Ack[j - (int) (lastSnWind - oldSn)];
					
				}
				
				//create the oldAck pkt
				UTPpacket sendUTPpkt = new UTPpacket();
				sendUTPpkt.dstAddr = clientAddr;
				sendUTPpkt.dstPort = (short) clientPort;
				sendUTPpkt.sn = booleansToInt(oldAck);			
				sendUTPpkt.lastSnInWindow = (int) oldSn;
				sendUTPpkt.payl = new byte[0];
				
				byte[] sendData = sendUTPpkt.getRawData();
				//send the ack 
				DatagramPacket sendPkt = new DatagramPacket(sendData, sendData.length, channelAddr, channelPort);  
				try{
					for(int j = 0; j<10; j++)	{
					socket.send(sendPkt);
					
					}
				}
				catch(IOException e) {
					System.err.println("I/O error while sending datagram:\n" + e);
					socket.close(); System.exit(-1);
				}
				System.out.println("Sent oldAck " + sendUTPpkt.lastSnInWindow);
				System.out.println("Ack string " + sendUTPpkt.sn);
				System.out.println("----------------------------------------");
				System.out.println("");
			}
			
			//---------------------------------------------------------------------------------------
			//---------------------Received the FIN packet -> set some variable--------------------
			
			if(recvUTPpkt.function == (byte) UTPpacket.FUNCT_FIN && first)	{		
				//gotFIN = true;
				snFIN = recvUTPpkt.sn;
				snFINWind = snFIN - firstSnWind;
				FinLength = recvUTPpkt.payl.length;
				FinPayl = recvUTPpkt.payl;
				System.out.println("FinPayl length: " + FinPayl.length);
				System.out.println("payl.length: " + recvUTPpkt.payl.length);
				first = false;
			}
				
				
				//-------for every packet of the window---------
				
				SN = recvUTPpkt.sn;				//update current SN 
				
				
				//Debug
				System.out.println("Received data: " + recvUTPpkt.payl);
				System.out.println("SN:" + recvUTPpkt.sn);				
				System.out.println("firstSnWind:" + firstSnWind);
				System.out.println("lastSnWind Client:" + recvUTPpkt.lastSnInWindow);
				System.out.println("lastSnWind Server:" + lastSnWind);
				System.out.println("DataBuffer length: " + DataBuffer.length);
				System.out.println("Window size: " + window_size);
				System.out.println("snFIN: " + snFIN);
				System.out.println("snFINWind: " + snFINWind);
				System.out.println("FinPayl length: " + FinLength);
				System.out.println("CountOld: " + countOld);
				if(snFIN != 0) System.out.println("IT'S A FIN!!!!!!! :-)");
				System.out.println("----------------------------------------");
				System.out.println("");
				
				
				//copy data in the cell of the buffer array with the right index
				if(recvUTPpkt.sn >= firstSnWind){
				try{System.arraycopy(recvUTPpkt.payl, 0, DataBuffer[(int) (recvUTPpkt.sn - firstSnWind)], 0 , recvUTPpkt.payl.length);
				}
				catch (ArrayIndexOutOfBoundsException e)	{
					System.err.println("recvUTPpkt.sn - firstSnWind: " + (recvUTPpkt.sn - firstSnWind));
					System.err.println("--------------------------");
					System.err.println("");
				}
				
				//put the 1 in the Ack array in the right position
				Ack[(int) (recvUTPpkt.sn - firstSnWind)] = true;
				}
				
				else	{
					System.out.println("Very old packet, DROPPED!!!");
					System.out.println("----------------------------------------");
					System.out.println("");
				}
					
	
			
				if(SN == lastSnWind)	{
					WINFIN = true;		//this is the last packet of the window
				}
			
			}
			
			
			
			//--------check the ACK array---------
			
			allTrue = areAllTrue(Ack);
			allFalse = areAllFalse(Ack);
			firstFalse = 0;
			
			//------Prepare the ACK packet------
			
			
			UTPpacket sendUTPpkt = new UTPpacket();
			sendUTPpkt.dstAddr = clientAddr;
			sendUTPpkt.dstPort = (short) clientPort;
			sendUTPpkt.sn = booleansToInt(Ack);			//WARNING: check this method with the client
			sendUTPpkt.lastSnInWindow = (int) lastSnWind;
			sendUTPpkt.payl = new byte[0];			
			sendUTPpkt.function = (byte) UTPpacket.FUNCT_ACKDATA;
				
			
			System.out.println("ACK ARRAY: " + Utils.AckToBinaryString(sendUTPpkt.sn, window_size));
			System.out.println("----------------------------------------");
			System.out.println("");
			
			byte[] sendData = sendUTPpkt.getRawData(); 	// payload of outgoing UDP datagram (UTP packet)

	
			
			
			
			//----------if ALL TRUE = true, proceed in the standard manner------------
			if(allTrue)	{
			
				int last = window_size;
				 
				if (snFIN != 0)	{
					
					last = window_size - 1;
					
				}
				
	
				//-----------write the data in the file buffer-----------------------
			for(int i = 0; i < last ; i++)	{
				try	{
					writeBuffer.write(DataBuffer[i]);
					n++;
					System.out.println("Writed1: " + n);
					System.out.println("last1: " + last);
					System.out.println("----------------------------------------");
					System.out.println("");
				}
				catch(IOException e)	{
					System.out.println("Error while putting data back together");
				}
				
				
				
	
				// If the buffer is too large, write it on file (append) and empty it.
				if (writeBuffer.size() > WRITEBUF_THRESH) {				//Is useful?
					writeBufferToFile(writeBuffer, fileOutputStream);
				}


			}
			//---------the FIN payload is shorter, then I've to manage that in a different manner---------
			if (snFIN != 0)	{				
				//try	{
					writeBuffer.write(FinPayl, 0, FinLength);
					n++;
					System.out.println("Writed the FIN payload 1. " + n);
					System.out.println("last1: " + last);
					System.out.println("Finlength: " + FinLength);
					System.out.println("----------------------------------------");
					System.out.println("");
					
				/*}
				catch(IOException e)	{
					System.out.println("Error while putting data back together");
				}*/
			gotFIN = true;
			
			}

			// --- Send ACK---
			DatagramPacket sendPkt = new DatagramPacket(sendData, sendData.length, channelAddr, channelPort);  
			try{
				for(int j = 0; j<10; j++)	{
				socket.send(sendPkt);
				}
			}
			catch(IOException e) {
				System.err.println("I/O error while sending datagram:\n" + e);
				socket.close(); System.exit(-1);
			}
			
			new_window = true;
			
			}		//------------if(AllTrue) finishes here-----------------
			
			
			
			
			
			//-----------if NOT ALL FALSE I store the first part of received data until the first not received packet-------
			else if(!allFalse)	{
			
			firstFalse = FirstFalse(Ack);
			
			long last = firstFalse;
			 
			
			if(snFIN != 0 && areAllTrue2(Ack, snFINWind))
				last = snFINWind;
			
			//write the first part of the received data in the file buffer
			for(int i = 0; i < last ; i++)	{
				try	{
					writeBuffer.write(DataBuffer[i]);
					n++;
					System.out.println("Writed2: " + n);
					System.out.println("last2: " + last);
					System.out.println("----------------------------------------");
					System.out.println("");
				}
				catch(IOException e)	{
					System.out.println("Error while putting data back together");
				}
	
			}
				if (snFIN != 0 && areAllTrue2(Ack, snFINWind))	{
					
					//try	{
						writeBuffer.write(FinPayl, 0, FinLength);
						n++;
						System.out.println("Writed the FIN payload 2. " + n);
						System.out.println("last2: " + last);
						System.out.println("Finlength: " + FinLength);
						System.out.println("----------------------------------------");
						System.out.println("");
					/*}
					catch(IOException e)	{
						System.out.println("Error while putting data back together");
					}*/
					gotFIN = true;
				}
				// If the buffer is too large, write it on file (append) and empty it.
				if (writeBuffer.size() > WRITEBUF_THRESH) {
					writeBufferToFile(writeBuffer, fileOutputStream);
				}

				// --- Send ACK---
				DatagramPacket sendPkt = new DatagramPacket(sendData, sendData.length, channelAddr, channelPort);  
				try{
					for(int j = 0; j<10; j++)	{
						socket.send(sendPkt);
						}
				}
				catch(IOException e) {
					System.err.println("I/O error while sending datagram:\n" + e);
					socket.close(); System.exit(-1);
				}
			
		
			notAllAcked = true;
			
		}//----------------- here finishes !allFalse--------------
		
		
		}
		System.out.println("Bye bye, Client! ;-)");
		System.out.println("Transfer time: " + (System.currentTimeMillis() - startTime)/1000 + " s");
		System.out.println("Transfered: " + ((n-1)*BLOCK_SIZE + FinLength)/1000 + "KBytes");
		System.out.println("Mean transfer speed: " + (double)((n-1)*BLOCK_SIZE + FinLength)/(double)(System.currentTimeMillis() - startTime) + "Kbytes/s");
		

		// Write the remaining data in the buffer to the file
				writeBufferToFile(writeBuffer, fileOutputStream);
		
		//create the FINACK pkt
		UTPpacket sendUTPpkt = new UTPpacket();
		sendUTPpkt.dstAddr = clientAddr;
		sendUTPpkt.dstPort = (short) clientPort;
		sendUTPpkt.sn = booleansToInt(Ack);			//WARNING: check this method with the client
		sendUTPpkt.lastSnInWindow = lastFinSn;
		sendUTPpkt.function = (byte) UTPpacket.FUNCT_ACKFIN;
		sendUTPpkt.payl = new byte[0];
		
		byte[] sendData = sendUTPpkt.getRawData();
		//send the ack 
		DatagramPacket sendPkt = new DatagramPacket(sendData, sendData.length, channelAddr, channelPort);  
		try{
			for(int j = 0; j<10; j++)	{
			socket.send(sendPkt);
			
			}
		}
		catch(IOException e) {
			System.err.println("I/O error while sending datagram:\n" + e);
			socket.close(); System.exit(-1);
		}
	
	


		

	}


	
	private static void writeBufferToFile(ByteArrayOutputStream buffer,
			FileOutputStream fileOutputStream) {
		
		System.out.println("\n   - - Writing " + buffer.size() + " bytes to disk");
		try {
			fileOutputStream.write(buffer.toByteArray());
			buffer.reset();
		} catch (IOException ioe) {
			ioe.printStackTrace();
		}
	}

	
	private static long booleansToInt(boolean[] arr){
	    long n = 0;
	    for (boolean b : arr)
	        n = (n << 1) | (b ? 1 : 0);
	    return n;
	}
	
	//-------methods for check the ACK array----------

	public static boolean areAllTrue(boolean[] array)
	{
	    for(boolean b : array) if(!b) return false;
	    return true;
	}
	
	public static boolean areAllTrue2(boolean[] array, long pos)
	{	
		
	    for(int i = 0; i < pos; i++) if (!array[i]) return false;
	    return true;
	}

	public static boolean areAllFalse(boolean[] array)
	{
	    for(boolean b : array) if(b) return false;
	    return true;
	}
	
	public static int FirstFalse(boolean[] array)
	{
	    for(int i = 0; i < array.length; i++ ) if(!array[i]) return i;
	    return array.length ;
	}

	//-----------methods for translating the buffer (Ack, data) windows--------------
	
	public static byte[][] Translation(byte[][] array, int pos, int size1, int size2)
	{
		byte[][] arraytemp = new byte[size1][size2];
		
		for(int i = pos; i < array.length; i++)	 {
		    arraytemp[i - pos] = array[i].clone();
		}
		return arraytemp;
	}
	
	public static boolean[] Translation(boolean[] array, int pos, int size)
	{
		boolean[] arraytemp = new boolean[size];
		
		for(int i = pos; i < array.length; i++)	 {
		    arraytemp[i - pos] = array[i];
		}
		return arraytemp;
	}
	
	
}
