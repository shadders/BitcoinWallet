BitcoinWallet
=============

BitcoinWallet is a Simple Payment Verification (SPV) Bitcoin wallet written in Java.  I wrote it mainly to get a better understanding of how the Bitcoin protocol works.  It allows you to send and receive coins using Pay-To-Pubkey-Hash payments.  It has a 'wallet' and a 'safe'.  The safe contains coins that are not to be spent until they are moved to the wallet.  It uses a single change address because I'm not worried about being anonymous on the network and don't want to take a chance on losing coins because I forgot to back up the wallet after making a transaction.  Bloom filters are used to reduce the amount of data sent to the wallet from the peer nodes.

I'm using PostgreSQL (9.3 or later) for the database simply because it is already installed for my JavaBitcoin project.  This is probably serious overkill for a normal wallet.  Since I am using a relational database, I decided to store the entire block chain going back to the genesis block (just the 80-byte headers).  That really isn't necessary but it allows me to verify the block chain in the same manner as a full node (although I still have to trust peers for transactions).  The SQL database could be replaced with a lightweight database such as LevelDB or even with a flat file encoded using something like ASN.1.

BouncyCastle (1.50 or later) is used for the elliptic curve functions and Simple Logging Facade (1.7.5 or later) is used for console and file logging.

Build
=====

I use the Netbeans IDE but any build environment with the Java compiler available should work.  The documentation is generated from the source code using javadoc.

Here are the steps for a manual build:

  - Create 'doc', 'lib' and 'classes' directories under the BitcoinWallet directory (the directory containing 'src')
  - Download Java SE Development Kit 7: http://www.oracle.com/technetwork/java/javase/downloads/index.html
  - Download BouncyCastle 1.50 or later to 'lib': https://www.bouncycastle.org/
  - Download Simple Logging Facade 1.7.5 or later to 'lib': http://www.slf4j.org/
  - Download PostgreSQL 9.3 or later to 'lib': http://www.postgresql.org/
  - Change to the BitcoinWallet directory (with subdirectories 'doc', 'lib', 'classes' and 'src')
  - The manifest.mf, build-list and doc-list files specify the classpath for the dependent jar files.  Update the list as required to match what you downloaded.
  - Build the classes: javac @build-list
  - Build the jar: jar cmf manifest.mf BitcoinWallet.jar -C classes BitcoinWallet
  - Build the documentation: javadoc @doc-list
