package me.aap.fermata.addon.felex.dict;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static me.aap.utils.async.Completed.completed;
import static me.aap.utils.async.Completed.completedVoid;
import static me.aap.utils.collection.NaturalOrderComparator.compareNatural;
import static me.aap.utils.misc.Assert.assertMainThread;
import static me.aap.utils.text.TextUtils.compareToIgnoreCase;
import static me.aap.utils.text.TextUtils.isBlank;

import androidx.annotation.NonNull;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import me.aap.fermata.addon.felex.FelexAddon;
import me.aap.utils.app.App;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.async.Promise;
import me.aap.utils.function.ToIntFunction;
import me.aap.utils.holder.IntHolder;
import me.aap.utils.io.CountingOutputStream;
import me.aap.utils.io.IoUtils;
import me.aap.utils.io.RandomAccessChannel;
import me.aap.utils.io.Utf8LineReader;
import me.aap.utils.io.Utf8Reader;
import me.aap.utils.log.Log;
import me.aap.utils.text.TextUtils;
import me.aap.utils.vfs.VirtualFile;

/**
 * @author Andrey Pavlenko
 */
public class Dict implements Comparable<Dict> {
	private static final String TAG_NAME = "#Name";
	private static final String TAG_SRC_LANG = "#SourceLang";
	private static final String TAG_TARGET_LANG = "#TargetLang";
	private static final String TAG_COUNT = "#Count";
	private static final int READ_CHUNK_SIZE = 10000;
	private static final int WRITE_CHUNK_SIZE = 1000;
	private final DictMgr mgr;
	private final VirtualFile dictFile;
	private final RandomAccessChannel channel;
	private final Locale sourceLang;
	private final Locale targetLang;
	private final String name;
	private boolean closed;
	private int wordsCount;
	private FutureSupplier<List<Word>> words;
	private ByteBuffer bb;
	private StringBuilder sb;
	private int dirProgress;
	private int revProgress;
	private RandomAccessChannel cacheChannel;
	private List<Word> progressUpdate;

	private Dict(DictMgr mgr, VirtualFile dictFile, RandomAccessChannel channel, String name,
							 Locale sourceLang, Locale targetLang, int wordsCount) {
		this.mgr = mgr;
		this.dictFile = dictFile;
		this.channel = channel;
		this.sourceLang = sourceLang;
		this.name = name;
		this.targetLang = targetLang;
		this.wordsCount = wordsCount;
	}

	public static FutureSupplier<Dict> create(DictMgr mgr, VirtualFile dictFile) {
		return mgr.enqueue(() -> {
			RandomAccessChannel ch = dictFile.getChannel("rw");
			if (ch == null)
				throw new IOException("Unable to read dictionary file: " + dictFile.getName());
			Utf8LineReader r = new Utf8LineReader(ch.getInputStream(0, 4096, ByteBuffer.allocate(256)));
			StringBuilder sb = new StringBuilder(64);
			String name = null;
			Locale srcLang = null;
			Locale targetLang = null;
			int count = 0;

			for (int i = r.readLine(sb); i != -1; sb.setLength(0), i = r.readLine(sb)) {
				if ((sb.length() == 0) || sb.charAt(0) != '#') break;
				if (TextUtils.startsWith(sb, TAG_NAME))
					name = sb.substring(TAG_NAME.length()).trim();
				else if (TextUtils.startsWith(sb, TAG_SRC_LANG))
					srcLang = new Locale(sb.substring(TAG_SRC_LANG.length()).trim());
				else if (TextUtils.startsWith(sb, TAG_TARGET_LANG))
					targetLang = new Locale(sb.substring(TAG_TARGET_LANG.length()).trim());
				else if (TextUtils.startsWith(sb, TAG_COUNT))
					count = Integer.parseInt(sb.substring(TAG_COUNT.length()).trim());
			}

			if ((name != null) && (srcLang != null) && (targetLang != null)) {
				return new Dict(mgr, dictFile, ch, name, srcLang, targetLang, count).readCacheHeader();
			}

			ch.close();
			throw new IllegalArgumentException("Invalid dictionary header: " + dictFile.getName());
		}).map(FutureSupplier::getOrThrow);
	}

	DictMgr getMgr() {
		return mgr;
	}

	public FutureSupplier<Word> getRandomWord(Random rnd, Deque<Word> history,
																						ToIntFunction<Word> getProgress) {
		return getWords().main().map(words -> {
			int s = words.size();
			if (s == 0) return null;
			int r = rnd.nextInt(s);
			Word min = words.get(r);
			int minProg = getProgress.applyAsInt(min);
			boolean minHistory = history.contains(min);

			if ((minProg != 0) || minHistory) {
				for (int i = r + 1; i != r; i++) {
					if ((i == s) && ((i = 0) == r)) break;
					Word w = words.get(i);

					if (history.contains(w)) {
						if (!minHistory) continue;
						int p = getProgress.applyAsInt(w);
						if (minProg <= p) continue;
						min = w;
						minProg = p;
					} else {
						int p = getProgress.applyAsInt(w);

						if (p == 0) {
							min = w;
							break;
						} else if (minHistory || (minProg > p)) {
							min = w;
							minProg = p;
							minHistory = false;
						}
					}
				}
			}

			if (history.peekLast() == min) history.clear();
			else if (history.size() == 10) history.pollLast();
			history.addFirst(min);
			return min;
		});
	}

	public FutureSupplier<List<Word>> getWords() {
		assertMainThread();
		if (isClosed()) throw new IllegalStateException();
		if (words != null) return words.fork();

		Promise<List<Word>> p = new Promise<>();
		words = p.then(w -> {
			List<Word> aligned = align(w);
			if ((aligned != null) || (wordsCount != w.size())) {
				return writeWords((aligned == null) ? w : aligned).then(v -> readWords(new Promise<>()));
			} else {
				return completed(w);
			}
		}).then(this::readCache).main().onCompletion((w, err) -> {
			if (isClosed()) return;
			if (err != null) {
				words = null;
				Log.e(err, "Failed to load words from ", dictFile.getName());
			} else {
				words = completed(w);
				wordsCount = w.size();
			}
		});

		readWords(p);
		return words.fork();
	}

	public String getName() {
		return name;
	}

	public Locale getSourceLang() {
		return sourceLang;
	}

	public Locale getTargetLang() {
		return targetLang;
	}

	public int getWordsCount() {
		return wordsCount;
	}

	public int getDirProgress() {
		return dirProgress / getWordsCount();
	}

	public int getRevProgress() {
		return revProgress / getWordsCount();
	}

	void incrProgress(Word w, boolean dir, int diff) {
		if (dir) dirProgress += diff;
		else revProgress += diff;
		if (progressUpdate == null) {
			progressUpdate = new ArrayList<>();
			App.get().getHandler().postDelayed(this::flush, 30000);
		}
		progressUpdate.add(w);
	}

	private FutureSupplier<?> flush() {
		if (progressUpdate == null) return completedVoid();
		List<Word> updates = progressUpdate;
		progressUpdate = null;
		int dirProg = getDirProgress();
		int revProg = getRevProgress();

		Log.d("Flushing ", updates.size(), " updates.");
		return getMgr().enqueue(() -> {
			RandomAccessChannel ch = cacheChannel;

			if (ch != null) {
				try {
					flush(ch, updates, dirProg, revProg);
					return completedVoid();
				} catch (Exception ex) {
					cacheChannel = null;
					ch.close();
					Log.e(ex, "Failed to flush updates to dictionary ", getName(), ". Retrying...");
				}
			}

			return getCacheFile(true).then(f -> getMgr().enqueue(() -> {
				flush(requireNonNull(cacheChannel = f.getChannel("rw")), updates, dirProg, revProg);
				return null;
			}));
		}).onFailure(err -> Log.e(err, "Failed to flush updates to dictionary: ", getName()));
	}

	private void flush(RandomAccessChannel ch, List<Word> updates,
										 int dirProg, int revProg) throws IOException {
		ByteBuffer bb = bb();
		bb.position(0).limit(10);
		bb.putShort(0, (short) 0);
		bb.putInt(2, dirProg);
		bb.putInt(6, revProg);
		if (ch.write(bb, 0) != bb.limit()) {
			throw new IOException("Failed to write cache header");
		}
		for (Word u : updates) flush(ch, bb, u);
	}

	private void flush(RandomAccessChannel ch, ByteBuffer bb, Word word) throws IOException {
		long off = word.getCacheOffset();
		byte dir = (byte) word.getDirProgress();
		byte rev = (byte) word.getRevProgress();

		if (off == 0) {
			Log.d("Adding to cache: ", word);
			off = ch.size();
			CountingOutputStream out = new CountingOutputStream(ch.getOutputStream(off));
			BufferedWriter w = new BufferedWriter(new OutputStreamWriter(out, UTF_8));
			w.append(word.getWord()).append('\0');
			w.flush();
			word.setCacheInfo(off, (int) out.getCount() + 2, dir, rev);
		} else {
			Log.d("Updating cache: ", word);
		}

		bb.position(0).limit(2);
		bb.put(0, dir);
		bb.put(1, rev);
		ch.write(bb, off + word.getCacheLen() - 2);
	}

	private Promise<List<Word>> readWords(Promise<List<Word>> p) {
		mgr.enqueue(() -> {
			StringBuilder sb = sb();
			Utf8LineReader r = new Utf8LineReader(channel.getInputStream(0, Long.MAX_VALUE, bb()));
			for (int i = r.readLine(sb); i != -1; sb.setLength(0), i = r.readLine(sb)) {
				if ((sb.length() == 0) || sb.charAt(0) != '#') break;
			}
			return readWords(p, new ArrayList<>(wordsCount), new IntHolder(), r, sb);
		}).onFailure(p::completeExceptionally);
		return p;
	}

	private List<Word> readWords(Promise<List<Word>> p, ArrayList<Word> list, IntHolder cnt,
															 Utf8LineReader r, StringBuilder sb) {
		try {
			for (int i = 0; i < READ_CHUNK_SIZE; i++) {
				long off = r.getBytesRead();
				sb.setLength(0);

				if (r.readLine(sb) == -1) {
					list.trimToSize();
					Collections.sort(list);
					p.complete(list);
					return list;
				}

				if (isBlank(sb) || (sb.charAt(0) == '\t')) continue;
				list.add(Word.create(sb, off));
			}

			cnt.value += WRITE_CHUNK_SIZE;
			p.setProgress(list, cnt.value, wordsCount);
			mgr.enqueue(() -> readWords(p, list, cnt, r, sb));
			return list;
		} catch (Exception ex) {
			p.completeExceptionally(ex);
			return list;
		}
	}

	List<Translation> readTranslations(Word w) throws IOException {
		Utf8LineReader r = new Utf8LineReader(channel.getInputStream(w.getOffset(), Long.MAX_VALUE, bb()));
		r.skipLine();
		StringBuilder sb = sb();
		List<Translation> list = new ArrayList<>();
		Example example = Example.DUMMY;
		Translation trans = Translation.DUMMY;

		for (; ; ) {
			sb.setLength(0);
			if (r.readLine(sb) == -1) break;
			if (isBlank(sb)) continue;
			if ((sb.charAt(0) != '\t')) break;

			if (sb.length() > 2) {
				if ((sb.charAt(1) == '\t')) {
					if (sb.charAt(2) == '\t') {
						example.setTranslation(sb.toString().trim());
					} else {
						example = new Example(sb.toString().trim());
						trans.addExample(example);
					}
					continue;
				}
			}

			trans = new Translation(sb.toString().trim());
			list.add(trans);
		}

		return list;
	}

	private FutureSupplier<List<Word>> writeWords(List<Word> words) {
		Log.i("Saving dictionary ", getName(), " to ", dictFile);
		Promise<List<Word>> p = new Promise<>();
		mgr.enqueue(() -> dictFile.getParent()
				.then(parent -> parent.createTempFile(dictFile.getName() + '-', ".tmp"))
				.then(tmp -> {
					try {
						p.onCompletion((r, err) -> {
							if (err != null) Log.e(err, "Failed to save dictionary: ", getName());
							else Log.i("Dictionary has been saved: ", getName());
							tmp.delete();
						});
						RandomAccessChannel ch = requireNonNull(tmp.getChannel("rw"));
						CountingOutputStream out = new CountingOutputStream(ch.getOutputStream(0));
						BufferedWriter w = new BufferedWriter(new OutputStreamWriter(out, UTF_8));
						w.append(TAG_NAME).append("\t\t").append(getName()).append('\n');
						w.append(TAG_SRC_LANG).append("\t").append(getSourceLang().toString()).append('\n');
						w.append(TAG_TARGET_LANG).append("\t").append(getTargetLang().toString()).append('\n');
						w.append(TAG_COUNT).append("\t\t").append(String.valueOf(words.size())).append("\n\n");
						writeWords(p, ch, out, w, words.iterator(), words);
					} catch (Exception ex) {
						p.completeExceptionally(ex);
					}
					return null;
				}));
		return p;
	}

	private List<Word> writeWords(Promise<List<Word>> p, RandomAccessChannel ch, CountingOutputStream out,
																BufferedWriter w, Iterator<Word> it, List<Word> words) {
		try {
			for (int i = 0; (i < WRITE_CHUNK_SIZE) && it.hasNext(); i++) {
				Word word = it.next();
				word.write(w, readTranslations(word));
				w.append('\n');
			}

			if (it.hasNext()) {
				mgr.enqueue(() -> writeWords(p, ch, out, w, it, words));
			} else {
				w.close();
				long len = out.getCount();
				mgr.enqueue(() -> {
					try {
						channel.transferFrom(ch, 0, 0, len);
						channel.truncate(len);
						p.complete(words);
					} catch (Exception ex) {
						p.completeExceptionally(ex);
					}
					return null;
				});
			}
		} catch (Exception ex) {
			p.completeExceptionally(ex);
		}

		return words;
	}

	private FutureSupplier<Dict> readCacheHeader() {
		assert cacheChannel == null;
		return getCacheFile(false).then(f -> (f == null) ? completed(this) : f.exists().then(b -> {
			if (!b) return completed(this);
			return getMgr().enqueue(() -> {
				if (isClosed()) return this;
				assert cacheChannel == null;
				RandomAccessChannel ch = cacheChannel = requireNonNull(f.getChannel("rw"));
				// Leading 2 bytes are reserved for the version code
				ByteBuffer bb = ByteBuffer.allocate(8);
				if (ch.read(bb, 2) != bb.limit()) {
					cacheChannel = null;
					ch.close();
					f.delete();
					throw new IOException("Dictionary cache corrupted: " + getName());
				}
				dirProgress = bb.getInt(0);
				revProgress = bb.getInt(4);
				return this;
			});
		})).ifFail(err -> {
			Log.e(err, "Failed to read dictionary cache file ", getName());
			return this;
		});
	}

	private FutureSupplier<List<Word>> readCache(List<Word> words) {
		RandomAccessChannel ch = cacheChannel;

		if (ch != null) {
			try {
				readCache(words, ch);
				return completed(words);
			} catch (Exception ex) {
				cacheChannel = null;
				ch.close();
				Log.e(ex, "Failed to read dictionary cache file ", getName(), ". Retrying...");
			}
		}

		return getCacheFile(false).then(f -> (f == null) ? completed(words) : f.exists().then(b -> {
			if (!b) return completed(words);
			return getMgr().enqueue(() -> {
				readCache(words, requireNonNull(cacheChannel = f.getChannel("rw")));
				return words;
			});
		}));
	}

	private void readCache(List<Word> words, RandomAccessChannel ch) throws IOException {
		int dirProgress = 0;
		int revProgress = 0;
		StringBuilder sb = sb();
		// Leading 2 bytes are reserved for the version code, the next 8 bytes - dir/rev progress
		Utf8Reader r = new Utf8Reader(ch.getInputStream(10, Long.MAX_VALUE, bb()));
		r.setBytesRead(10);

		read:
		for (int high = words.size() - 1; ; ) {
			long start = r.getBytesRead();
			sb.setLength(0);

			for (int c = r.read(); ; c = r.read()) {
				if (c == -1) break read;
				else if (c == '\0') break;
				sb.append((char) c);
			}

			byte dir = (byte) r.read();
			byte rev = (byte) r.read();

			if ((dir < 0) || (rev < 0)) {
				Log.e("Dictionary cache corrupted: " + getName());
				ch.truncate(start);
				return;
			}

			int len = (int) (r.getBytesRead() - start);
			int i = findWord(words, sb, high);

			if (i < 0) {
				Log.i("Removing from cache: ", sb);
				ch.transferFrom(ch, start + len, start, ch.size() - start - len);
				ch.truncate(ch.size() - len);
				r.setBytesRead(start);
			} else {
				dirProgress += dir;
				revProgress += rev;
				words.get(i).setCacheInfo(start, len, dir, rev);
			}
		}

		this.dirProgress = dirProgress;
		this.revProgress = revProgress;
	}

	private FutureSupplier<VirtualFile> getCacheFile(boolean create) {
		return FelexAddon.get().getCacheFolder().then(dir -> {
			String name = dictFile.getName() + ".cache";
			return create ? dir.createFile(name) : dir.getChild(name).cast();
		});
	}

	private static int findWord(List<Word> words, StringBuilder word, int high) {
		int low = 0;
		while (low <= high) {
			int mid = (low + high) >>> 1;
			int cmp = compareToIgnoreCase(words.get(mid).getWord(), word);
			if (cmp < 0) low = mid + 1;
			else if (cmp > 0) high = mid - 1;
			else return mid;
		}
		return -(low + 1);
	}

	@Override
	public int compareTo(Dict o) {
		return compareNatural(getName(), o.getName());
	}

	public FutureSupplier<?> close() {
		assertMainThread();
		if (isClosed()) return completedVoid();
		return flush().then(v -> mgr.enqueue(() -> {
			IoUtils.close(channel, cacheChannel);
			cacheChannel = null;
			bb = null;
			sb = null;
			return null;
		})).main().onCompletion((r2, err2) -> {
			if (err2 != null) Log.e(err2, "Failed to close dictionary: ", getName());
			words = null;
			closed = true;
		});
	}

	public boolean isClosed() {
		return closed;
	}

	@NonNull
	@Override
	public String toString() {
		return getName() + " (" + getSourceLang().getLanguage().toUpperCase()
				+ '-' + getTargetLang().getLanguage().toUpperCase() + ')';
	}


	private ByteBuffer bb() {
		ByteBuffer b = bb;
		if (b == null) bb = b = ByteBuffer.allocateDirect(8192);
		return b;
	}

	private StringBuilder sb() {
		StringBuilder b = sb;
		if (b == null) sb = b = new StringBuilder(512);
		b.setLength(0);
		return b;
	}

	private List<Word> align(List<Word> words) {
		if (words.isEmpty()) return null;
		int dups = 0;
		boolean align = false;
		Iterator<Word> it = words.listIterator();
		Word prev = it.next();

		while (it.hasNext()) {
			Word w = it.next();
			if (w.getWord().equals(prev.getWord())) dups++;
			else if (prev.getOffset() >= w.getOffset()) align = true;
			prev = w;
		}

		if (dups != 0) {
			List<Word> aligned = new ArrayList<>(words.size() - dups);
			it = words.listIterator();
			prev = it.next();
			aligned.add(prev);

			while (it.hasNext()) {
				Word w = it.next();
				if (!w.getWord().equals(prev.getWord())) aligned.add(w);
				prev = w;
			}

			return aligned;
		} else if (align) {
			return words;
		} else {
			return null;
		}
	}
}