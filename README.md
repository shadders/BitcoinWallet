BitcoinWallet
=============

BitcoinWallet is a Simple Payment Verification (SPV) Bitcoin wallet written in Java.  I wrote it mainly to get a better understanding of how the Bitcoin protocol works.  It allows you to send and receive coins using Pay-To-Pubkey-Hash payments.  It has a 'wallet' and a 'safe'.  The safe contains coins that are not to be spent until they are moved to the wallet.  It uses a single change address because I'm not worried about being anonymous on the network and don't want to take a chance on losing coins because I forgot to back up the wallet after making a transaction.  Bloom filters are used to reduce the amount of data sent to the wallet from the peer nodes.

I'm using PostgreSQL (9.3 or later) for the database simply because it is already installed for my JavaBitcoin project.  This is probably serious overkill for a normal wallet.  Since I am using a relational database, I decided to store the entire block chain going back to the genesis block (just the 80-byte headers).  That really isn't necessary but it allows me to verify the block chain in the same manner as a full node (although I still have to trust peers for transactions).  The SQL database could be replaced with a lightweight database such as LevelDB or even with a flat file encoded using something like ASN.1.

BouncyCastle (1.50 or later) is used for the elliptic curve functions and Simple Logging Facade (1.7.5 or later) is used for console and file logging.

There are no special build instructions.  I use the Netbeans IDE but any build environment with the Java compiler available should work.  The documentation is generated from the source code using javadoc.
