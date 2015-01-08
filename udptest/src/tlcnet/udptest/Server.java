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

	private static final int WINDOW_TIMEOUT = 2000; //To stop waiting the pck of the window and send the ack
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
		int lastSnWind = 0;
		int firstSnWind = 1;			//first sn in the current window
		int SN = 1;						//is useless
		int window_size = 10;
		byte[][] DataBuffer = new byte[window_size][BLOCK_SIZE];			//data buffer while receiving the window's packets 
		boolean[] Ack = new boolean[window_size];
		long startTransferTime = System.currentTimeMillis();				//
		boolean allTrue = true;
		boolean allFalse = false;
		int firstFalse = 0;
		int snFIN = 0;
		int snFINWind = 0;
		int FinLength = 0;
		byte[] FinPayl = new byte[BLOCK_SIZE];
		int n = 0;
		
		boolean gotFIN = false; //Needed to stop the cycle
		while(!gotFIN)
		{
			
			startTransferTime = System.currentTimeMillis();	
			
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
				
				//window_size = recvUTPpkt.lastSnInWindow - lastSnWind; //get the current window size
				firstSnWind = lastSnWind + 1;						
				lastSnWind = recvUTPpkt.lastSnInWindow;
				startTransferTime = System.currentTimeMillis();			//start the timer
				DataBuffer = new byte [window_size][BLOCK_SIZE];
				Ack = new boolean[window_size];
				
				new_window = false;
			}	
			
			//------if some packets of the previous window are lost----------
			
			else if(notAllAcked){
					
				if (recvUTPpkt.lastSnInWindow > lastSnWind){
				//window_size = recvUTPpkt.lastSnInWindow - (firstSnWind + firstFalse - 1); //get the current window size
				firstSnWind = firstSnWind + firstFalse;							
				lastSnWind = recvUTPpkt.lastSnInWindow;
				startTransferTime = System.currentTimeMillis();			//start the timer
				DataBuffer = Translation(DataBuffer, firstFalse, window_size, BLOCK_SIZE);//translate the buffer
				Ack  = Translation(Ack, firstFalse, window_size);
				new_window = false;
				notAllAcked = false;
				
				
				if (snFIN != 0)	{
					snFINWind = snFIN- firstSnWind;
				}
				
			}
			}
			if(recvUTPpkt.function == (byte) UTPpacket.FUNCT_FIN )	{		//received the FIN packet
				//gotFIN = true;
				snFIN = recvUTPpkt.sn;
				snFINWind = snFIN - firstSnWind;
				FinLength = recvUTPpkt.payl.length;
				FinPayl = recvUTPpkt.payl;
				
				
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
				if(snFIN != 0) System.out.println("IT'S A FIN!!!!!!! :-)");
				System.out.println("----------------------------------------");
				System.out.println("");
				
				
				//copy data in the cell of the buffer array with the right index
				if(recvUTPpkt.sn >= firstSnWind && lastSnWind <= recvUTPpkt.lastSnInWindow){
				try{System.arraycopy(recvUTPpkt.payl, 0, DataBuffer[recvUTPpkt.sn - firstSnWind], 0 , recvUTPpkt.payl.length);
				}
				catch (ArrayIndexOutOfBoundsException e)	{
					System.err.println("recvUTPpkt.sn - firstSnWind: " + (recvUTPpkt.sn - firstSnWind));
					System.err.println("--------------------------");
					System.err.println("");
				}
				
				//put the 1 in the Ack array in the right position
				Ack[recvUTPpkt.sn - firstSnWind] = true;
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
			
			//------ACK packet------
			
			
			UTPpacket sendUTPpkt = new UTPpacket();
			sendUTPpkt.dstAddr = clientAddr;
			sendUTPpkt.dstPort = (short) clientPort;
			sendUTPpkt.sn = booleansToInt(Ack);			//WARNING: check this method with the client
			sendUTPpkt.lastSnInWindow = lastSnWind;
			sendUTPpkt.payl = new byte[0];
			if(gotFIN && FirstFalse(Ack)== (snFINWind + 1))	{						
				sendUTPpkt.function = (byte) UTPpacket.FUNCT_ACKFIN;
				//gotFIN = true;
			}
			else
			{
				sendUTPpkt.function = (byte) UTPpacket.FUNCT_ACKDATA;
				//gotFIN = false;
				}
			
			System.out.println("ACK ARRAY: " + Integer.toBinaryString(sendUTPpkt.sn));
			System.out.println("----------------------------------------");
			System.out.println("");
			
			byte[] sendData = sendUTPpkt.getRawData(); 	// payload of outgoing UDP datagram (UTP packet)

	
			
			
			
			//----------if ALL TRUE = true, proceed in the standard manner------------
			if(allTrue)	{
			
				int last = window_size;
				 
				if (snFIN != 0)	{
					
					last = window_size - 1;
					
				}
				
	
				
			for(int i = 0; i < last ; i++)	{
				try	{
					writeBuffer.write(DataBuffer[i]);
					n++;
					System.out.println("Writed: " + n);
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
			if (snFIN != 0)	{				
				try	{
					writeBuffer.write(FinPayl);
					System.out.println("Writed the FIN payload 1. " + n);
					System.out.println("----------------------------------------");
					System.out.println("");
					n++;
				}
				catch(IOException e)	{
					System.out.println("Error while putting data back together");
				}
			gotFIN = true;
			
			}

			// --- Send ACK---
			DatagramPacket sendPkt = new DatagramPacket(sendData, sendData.length, channelAddr, channelPort);  
			try{
				for(int j = 0; j<3; j++)	{
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
			
			int last = firstFalse;
			 
			//TODO:Devo controllare che abbia ricevuto tutti i pacchetti fino a quello del fin
			if(snFIN != 0 && areAllTrue2(Ack, snFINWind))
				last = snFINWind - 1;
			
			for(int i = 0; i < last ; i++)	{
				try	{
					writeBuffer.write(DataBuffer[i]);
					n++;
					System.out.println("Writed: " + n);
					System.out.println("----------------------------------------");
					System.out.println("");
				}
				catch(IOException e)	{
					System.out.println("Error while putting data back together");
				}
	
			}
				if (snFIN != 0 && areAllTrue2(Ack, snFINWind))	{
					
					try	{
						writeBuffer.write(FinPayl);
						System.out.println("Writed the FIN payload 2. " + n);
						System.out.println("----------------------------------------");
						System.out.println("");
						n++;
					}
					catch(IOException e)	{
						System.out.println("Error while putting data back together");
					}
					gotFIN = true;
				}
				// If the buffer is too large, write it on file (append) and empty it.
				if (writeBuffer.size() > WRITEBUF_THRESH) {
					writeBufferToFile(writeBuffer, fileOutputStream);
				}

				// --- Send ACK---
				DatagramPacket sendPkt = new DatagramPacket(sendData, sendData.length, channelAddr, channelPort);  
				try{
					for(int j = 0; j<3; j++)	{
						socket.send(sendPkt);
						}
				}
				catch(IOException e) {
					System.err.println("I/O error while sending datagram:\n" + e);
					socket.close(); System.exit(-1);
				}
			
		
			notAllAcked = true;
			
		}//----------------- here finishes allFalse--------------
		
		
		}
		System.out.println("Bye bye, Client! ;-)");




		// Write the remaining data in the buffer to the file
		writeBufferToFile(writeBuffer, fileOutputStream);

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

	
	private static int booleansToInt(boolean[] arr){
	    int n = 0;
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
	
	public static boolean areAllTrue2(boolean[] array, int pos)
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
