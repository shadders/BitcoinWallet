BitcoinWallet
=============

BitcoinWallet is a Simple Payment Verification (SPV) Bitcoin wallet written in Java.  It allows you to send and receive coins using Pay-To-Pubkey-Hash payments.  It has a 'wallet' and a 'safe'.  The safe contains coins that are not to be spent until they are moved to the wallet.  It uses a single change address because I'm not worried about being anonymous on the network and don't want to take a chance on losing coins because I forgot to back up the wallet after making a transaction.  Bloom filters are used to reduce the amount of data sent to the wallet from the peer nodes.

Support is provided for the payment protocols defined in BIP0021 and BIP0070.  For a BIP0020 payment request, the transaction is created and broadcast after being confirmed by the user.  For a BIP0070 payment request, the transaction is created and returned to the merchant after being confirmed by the user.  A BIP0070 payment request that doesn't supply a payment URL will be broadcast immediately.  Otherwise, the transaction will not be broadcast until the acknowledgement is received from the merchant.  This ensures that the merchant receives the payment and no coins are lost if an error occurs during the payment processing.  BitcoinWallet must be registered to handle the bitcoin URI and must not be running when the payment request is made since the web browser will launch the application when it receives the payment request.

You can use the production network (PROD) or the regression test network (TEST).  The regression test network is useful because bitcoind will immediately generate a specified number of blocks.  To use the regression test network, start bitcoind with the -regtest option.  You can then generate blocks using bitcoin-cli to issue 'setgenerate true n' where 'n' is the number of blocks to generate.  Block generation will stop after the requested number of blocks have been generated.  Note that the genesis block, address formats and magic numbers are different between the two networks.  BitcoinWallet will create files related to the TEST network in the TestNet subdirectory of the application data directory.

LevelDB is used for the wallet database and the files will be stored in the LevelDB subdirectory of the application data directory.  The LevelDB support is provided by leveldbjni (1.8 or later).  leveldbjni provides a native interface to the LevelDB routines.  The native LevelDB library is included in the leveldbjni.jar file and is extracted when you run the program.  On Windows, this causes a new temporary file to be created each time the program is run.  To get around this, extract the Windows version of leveldbjni.dll from the leveldbjni.jar and place it in a directory in the executable path (specified by the PATH environment variable).  Alternately, you can define the path to leveldbjni.dll by specifying '-Djava.library.path=directory-path' on the command line used to start BitcoinWallet.

BouncyCastle (1.51 or later) is used for the elliptic curve functions.  Version 1.51 provides a custom SecP256K1 curve which significantly improves ECDSA performance.  Earlier versions of BouncyCastle do not provide this support and will not work with JavaBitcoin.

Simple Logging Facade (1.7.5 or later) is used for console and file logging.  I'm using the JDK logger implementation which is controlled by the logging.properties file located in the application data directory.  If no logging.properties file is found, the system logging.properties file will be used (which defaults to logging to the console only).

Google Protocol Buffers are used for the BIP0070 payment protocol support.  You can learn more about protocol buffers at https://developers.google.com/protocol-buffers/.

A compiled version of BitcoinWallet is available here: https://drive.google.com/folderview?id=0B1312_6UqRHPYjUtbU1hdW9VMW8&usp=sharing.  Download the desired archive file and extract the files to a directory of your choice.  If you are building from the source, the dependent jar files can also be obtained here.


Build
=====

I use the Netbeans IDE but any build environment with the Java compiler available should work.  The documentation is generated from the source code using javadoc.

Here are the steps for a manual build:

  - Create 'doc', 'lib' and 'classes' directories under the BitcoinWallet directory (the directory containing 'src')
  - Download Java SE Development Kit 7: http://www.oracle.com/technetwork/java/javase/downloads/index.html
  - Download BouncyCastle 1.51 or later to 'lib': https://www.bouncycastle.org/
  - Download Simple Logging Facade 1.7.5 or later to 'lib': http://www.slf4j.org/
  - Download leveldbjni 1.8 or later to 'lib': http://repo2.maven.org/maven2/org/fusesource/leveldbjni/leveldbjni-all/1.8/
  - Download Protocol Buffers 2.5.0 or later to 'lib': https://developers.google.com/protocol-buffers/downloads/.  You just need the jar file unless you want to recompile paymentrequest.proto (a compiled Protos.java is included in the source directory)
  - Change to the BitcoinWallet directory (with subdirectories 'doc', 'lib', 'classes' and 'src')
  - The manifest.mf, build-list and doc-list files specify the classpath for the dependent jar files.  Update the list as required to match what you downloaded.
  - Build the classes: javac @build-list
  - Build the jar: jar cmf manifest.mf BitcoinWallet.jar -C classes . -C resources .
  - Build the documentation: javadoc @doc-list
  - Copy BitcoinWallet.jar and the 'lib' directory to wherever you want to store the executables.
  - Create a shortcut to start BitcoinWallet using java.exe for a command window or javaw.exe for GUI only.  


Runtime Options
===============

The following command-line arguments are supported:
	
  - PROD	
    Start the program using the production network. Application files are stored in the application data directory and the production database is used. DNS discovery will be used to locate peer nodes.
	
  - TEST	
    Start the program using the regression test network. Application files are stored in the TestNet folder in the application data directory and the test database is used. At least one peer node must be specified in BitcoinWallet.conf since DNS discovery is not supported for the regression test network.

The following command-line options can be specified using -Dname=value

  - bitcoin.datadir=directory-path		
    Specifies the application data directory. Application data will be stored in a system-specific directory if this option is omitted:		
	    - Linux: user-home/.BitcoinWallet	
		- Mac: user-home/Library/Application Support/BitcoinWallet	
		- Windows: user-home\AppData\Roaming\BitcoinWallet	
	
  - java.util.logging.config.file=file-path		
    Specifies the logger configuration file. The logger properties will be read from 'logging.properties' in the application data directory. If this file is not found, the 'java.util.logging.config.file' system property will be used to locate the logger configuration file. If this property is not defined, the logger properties will be obtained from jre/lib/logging.properties.
	
    JDK FINE corresponds to the SLF4J DEBUG level	
	JDK INFO corresponds to the SLF4J INFO level	
	JDK WARNING corresponds to the SLF4J WARN level		
	JDK SEVERE corresponds to the SLF4J ERROR level		

The following configuration options can be specified in BitcoinWallet.conf.  This file is optional and must be in the application directory in order to be used.

	- connect=[address]:port		
	  Specifies the address and port of a peer node.  This statement can be repeated to define multiple nodes.  If this option is specified, connections will be created to only the listed addresses and DNS discovery will not be used.
		
Sample Windows shortcut:	

	javaw.exe -Xmx256m -Djava.library.path=\Bitcoin\BitcoinWallet -jar \Bitcoin\BitcoinWallet\BitcoinWallet.jar PROD

The leveldbjni.dll file was extracted from the jar file and placed in the \Bitcoin\BitcoinWallet directory.  Specifying java.library.path tells the JVM where to find the native resources.
