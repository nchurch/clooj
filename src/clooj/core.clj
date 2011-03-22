; Copyright (c) 2011, Arthur Edelstein
; All rights reserved.
; arthuredelstein@gmail.com

(ns clooj.core
  (:import (javax.swing BorderFactory JFrame JLabel JPanel JScrollPane JList
                        JMenuBar JMenu JMenuItem KeyStroke JSplitPane JTextField
                        JTextArea SpringLayout AbstractListModel AbstractAction
                        UIManager JTree)
           (javax.swing.event CaretListener DocumentListener TreeSelectionListener
                              UndoableEditListener)
           (javax.swing.text DefaultHighlighter
                             DefaultHighlighter$DefaultHighlightPainter
                             DocumentFilter)
           (javax.swing.tree DefaultMutableTreeNode DefaultTreeModel)
           (javax.swing.undo UndoManager)
           (java.awt Insets Rectangle)
           (java.awt.event ActionListener FocusAdapter KeyEvent KeyListener)
           (java.awt Color Font Toolkit FileDialog)
           (java.util UUID)
           (java.util.prefs Preferences)
           (java.io File FilenameFilter FileReader FileWriter OutputStream
                    OutputStreamWriter PipedReader PipedWriter PrintWriter
                    StringReader Writer ByteArrayOutputStream ObjectOutputStream
                    ObjectInputStream ByteArrayInputStream)
           (clojure.lang LineNumberingPushbackReader))
  (:use [clojure.contrib.duck-streams :only (writer)]
        [clojure.pprint :only (pprint)])
  (:require [clojure.contrib.string :as string]
            [clojure.main :only (repl repl-prompt)])
  (:gen-class))

;; preferences

(def clooj-prefs (.. Preferences userRoot (node "clooj1")))

(defn partition-str [n s]
  (loop [rem s acc []]
    (if (pos? (.length rem))
      (recur (clojure.contrib.string/drop n rem)
             (conj acc (clojure.contrib.string/take n rem)))
      (seq acc))))

(def pref-max-bytes (* 3/4 Preferences/MAX_VALUE_LENGTH))

(defn write-value-to-prefs
  "Reads a pure clojure data structure from Preferences object."
  [prefs key value]
  (let [chunks (partition-str pref-max-bytes (with-out-str (pr value)))
        node (. prefs node key)]
    (.clear node)
    (doseq [i (range (count chunks))]
       (. node put (str i) (nth chunks i)))))

(defn read-value-from-prefs
  "Writes a pure clojure data structure to Preferences object."
  [prefs key]
  (let [node (. prefs node key)]
    (read-string
      (apply str
             (for [i (range (count (. node keys)))]
               (.get node (str i) nil))))))

;; utils

(def gap 5)

(defn count-while [pred coll]
  (count (take-while pred coll)))

(defn get-mono-font []
  (Font. "Monaco" Font/PLAIN 11))

(defn make-text-area []
  (doto (JTextArea.)
    (.setFont (get-mono-font))))

(defn get-coords [text-comp offset]
  (let [row (.getLineOfOffset text-comp offset)
        col (- offset (.getLineStartOffset text-comp row))]
    {:row row :col col}))

(defn add-text-change-listener [text-comp f]
  "Executes f whenever text is changed in text component."
  (.addDocumentListener
    (.getDocument text-comp)
    (reify DocumentListener
      (insertUpdate [this _] (f))
      (removeUpdate [this _] (f))
      (changedUpdate [this _] (f)))))

(defn set-selection [text-comp start end]
  (doto text-comp (.setSelectionStart start) (.setSelectionEnd end)))

;; REPL stuff
;; adapted from http://clojure101.blogspot.com/2009/05/creating-clojure-repl-in-your.html

(def repl-history {:items (atom nil) :pos (atom 0)})

(def *printStackTrace-on-error* false)

(defn is-eof-ex? [throwable]
  (and (instance? clojure.lang.LispReader$ReaderException throwable)
  (or
    (.startsWith (.getMessage throwable) "java.lang.Exception: EOF while reading")
    (.startsWith (.getMessage throwable) "java.io.IOException: Write end dead"))))

(defn create-clojure-repl [result-writer]
  "This function creates an instance of clojure repl, with output going to output-writer
  Returns an input writer."
  (let [first-prompt (atom true)
        input-writer (PipedWriter.)
        piped-in (clojure.lang.LineNumberingPushbackReader. (PipedReader. input-writer))
        piped-out (PrintWriter. result-writer)
        repl-thread-fn #(binding [*printStackTrace-on-error* *printStackTrace-on-error*
                                  *in* piped-in
                                  *out* piped-out
                                  *err* *out*]
               (try
                 (clojure.main/repl
                   :init (fn [] (in-ns 'user))
                   :print (fn [& args] (doall (map pprint args)))
                   :read (fn [prompt exit]
                           (read))
                   :caught (fn [e]
                             (when (is-eof-ex? e)
                               (throw e))
                             (if *printStackTrace-on-error*
                               (.printStackTrace e *out*)
                               (prn (clojure.main/repl-exception e)))
                             (flush))
                   :prompt (fn [] (if @first-prompt (reset! first-prompt false) (println))
                                  (clojure.main/repl-prompt))
                   :need-prompt (constantly true))
                 (catch clojure.lang.LispReader$ReaderException ex
                   (prn "REPL closing"))
                 (catch java.lang.InterruptedException ex)
                 (catch java.nio.channels.ClosedByInterruptException ex)))]
    (.start (Thread. repl-thread-fn))
    input-writer))

(defn replace-first [coll x]
  (cons x (next coll)))

(defn update-repl-history [doc]
  (swap! (:items repl-history) replace-first
         (.getText (doc :repl-in-text-area))))

(defn send-to-repl [doc cmd]
  (let [cmd-ln (str (.trim cmd) \newline)]
    (.append (doc :repl-out-text-area) cmd-ln)
    (.write (doc :repl-writer) cmd-ln)
    (.flush (doc :repl-writer))
    (swap! (:items repl-history)
           replace-first (.trim cmd-ln))
    (reset! (:pos repl-history) 0)
    (swap! (:items repl-history) conj "")))

(defn send-selected-to-repl [doc]
  (send-to-repl doc (.getSelectedText (doc :doc-text-area))))

(defn scroll-to-last [text-area]
  (.scrollRectToVisible text-area
    (Rectangle. 0 (.getHeight text-area) 1 1)))

;; caret finding

(defn get-caret-position [text-comp]
  (get-coords text-comp (.getCaretPosition text-comp)))

(defn display-caret-position [doc]
  (let [{:keys [row col]} (get-caret-position (:doc-text-area doc))]
    (.setText (:pos-label doc) (str " " (inc row) "|" (inc col)))))

;; bracket handling

(defn bracket-score [c]
  (condp = c 
         \(  1 \[  1 \{  1
         \) -1 \] -1 \} -1
         0))

(defn bracket-increment [score next-char]
  (+ score (bracket-score next-char)))

(defn count-brackets [s]
  (reductions bracket-increment 0 s))

(defn find-left-enclosing-bracket [text pos]
  (let [before (string/take pos text)]
    (- pos (count-while
             (partial >= 0)
             (count-brackets (string/reverse before))))))

(defn find-right-enclosing-bracket [text pos]
  (let [after (string/drop pos text)]
    (+ -1 pos (count-while
                (partial <= 0)
                (count-brackets after)))))

(defn find-enclosing-brackets [text pos]
  [(find-left-enclosing-bracket text pos)
   (find-right-enclosing-bracket text pos)])

(defn mismatched-brackets [a b]
  (and (or (nil? a) (some #{a} [\( \[ \{]))
       (some #{b} [\) \] \}])
       (not (some #{[a b]} [[\( \)] [\[ \]] [\{ \}]]))))

(defn find-bad-brackets [text]
  (loop [t text cnt 0 stack nil errs nil]
    (let [s stack
          c (first t)
          l (ffirst s)
          p (next s)
          j (conj s [c cnt])
          new-stack
            (condp = l
              \\ p
              \" (if (= c \") p s)
              \; (if (= c \newline) p s)
              (condp = c
                \" j \\ j \; j
                \( j \[ j \{ j
                \) p \] p \} p
                s))
          e (if (mismatched-brackets l c)
              (list (first s) [c cnt]))
          new-errs (if e (concat errs e) errs)]
        (if (next t)
          (recur (next t) (inc cnt) new-stack new-errs)
          (filter identity
                  (map second (concat new-stack errs)))))))

;; repl stuff

(defn make-repl-writer [ta-out]
  (let [buf (StringBuffer.)]
    (proxy [Writer] []
      (write
        ([char-array offset length]
          (.append buf char-array offset length))
        ([t]
          (when (= Integer (type t))
            (.append buf (char t)))))
      (flush [] (.append ta-out (.toString buf))
                (scroll-to-last ta-out)
                (.setLength buf 0))
      (close [] nil))))

(defn update-repl-in [doc]
  (when (pos? (count @(:items repl-history)))
    (.setText (:repl-in-text-area doc)
      (nth @(:items repl-history) @(:pos repl-history)))))

(defn show-previous-repl-entry [doc]
  (when (zero? @(:pos repl-history))
        (update-repl-history doc))
  (swap! (:pos repl-history)
         #(Math/min (dec (count @(:items repl-history))) (inc %)))
  (update-repl-in doc))

(defn show-next-repl-entry [doc]
  (swap! (:pos repl-history)
         #(Math/max 0 (dec %)))
  (update-repl-in doc))

(defn attach-child-action-key
  "Maps an input-key on a swing component to an action,
  such that action-fn is executed when pred function is
  true, but the parent (default) action when pred returns
  false."
  [component input-key pred action-fn]
  (let [im (.getInputMap component)
        am (.getActionMap component)
        input-event (KeyStroke/getKeyStroke input-key)
        parent-action (if-let [tag (.get im input-event)]
                        (.get am tag))
        child-action
          (proxy [AbstractAction] []
            (actionPerformed [e]
              (if (pred)
                (action-fn)
                (when parent-action
                  (.actionPerformed parent-action e)))))
        uuid (.. UUID randomUUID toString)]
    (.put im input-event uuid)
    (.put am uuid child-action)))


(defn attach-child-action-keys [comp & items]
  (doall (map #(apply attach-child-action-key comp %) items)))

(defn attach-action-key
  "Maps an input-key on a swing component to an action-fn."
  [component input-key action-fn]
  (attach-child-action-key component input-key
                           (constantly true) action-fn))

(defn attach-action-keys [comp & items]
  "Maps input keys to action-fns."
  (doall (map #(apply attach-action-key comp %) items)))

(defn add-repl-input-handler [doc]
  (let [ta-in (doc :repl-in-text-area)
        get-caret-pos #(.getCaretPosition ta-in)
        ready #(let [caret-pos (get-caret-pos)]
                 (and
                   (= (.. ta-in getDocument getLength)
                                caret-pos)
                   (= -1 (find-left-enclosing-bracket
                           (.getText ta-in)
                           caret-pos))))
        submit #(do (send-to-repl doc (.getText ta-in))
                    (.setText ta-in ""))
        at-top #(zero? (.getLineOfOffset ta-in (get-caret-pos)))
        at-bottom #(= (.getLineOfOffset ta-in (get-caret-pos))
                      (.getLineOfOffset ta-in (.. ta-in getText length)))
        prev-hist #(show-previous-repl-entry doc)
        next-hist #(show-next-repl-entry doc)]
    (attach-child-action-keys ta-in ["UP" at-top prev-hist]
                                    ["DOWN" at-bottom next-hist]
                                    ["ENTER" ready submit])
    (attach-action-keys ta-in ["meta UP" prev-hist]
                              ["meta DOWN" next-hist]
                              ["meta ENTER" submit])))

(defn apply-namespace-to-repl [doc]
  (try
    (when-let [sexpr (read-string (. (doc :doc-text-area)  getText))]
      (when (= 'ns (first sexpr))
        (send-to-repl doc (str "(ns " (second sexpr) ")"))))
    (catch Exception e)))
  
;; highlighting

(def caret-highlight (atom nil))

(defn highlight
  ([text-comp start stop color]
    (.addHighlight (.getHighlighter text-comp)
                   start stop
                   (DefaultHighlighter$DefaultHighlightPainter. color)))
  ([text-comp pos color] (highlight text-comp pos (inc pos) color)))

(defn remove-highlight
  ([text-comp highlight-object]
    (.removeHighlight (.getHighlighter text-comp)
                      highlight-object)))

(defn remove-highlights
  ([text-comp highlights]
    (dorun (map #(remove-highlight text-comp %) highlights))))

(defn highlight-enclosing-brackets [text-comp pos color]
  (doall (map #(highlight text-comp % color)
       (find-enclosing-brackets (.getText text-comp) pos))))

(defn highlight-caret-enclosure [text-comp]
  (when-let [ch @caret-highlight]
    (doall (map #(remove-highlight text-comp %) ch)))
  (reset! caret-highlight
          (highlight-enclosing-brackets
            text-comp (.getCaretPosition text-comp) Color/LIGHT_GRAY)))

(defn highlight-bad-brackets [text-comp]
  (doall (map #(highlight text-comp % Color/PINK)
    (find-bad-brackets (.getText text-comp)))))

(defn add-caret-listener [text-comp f]
  (.addCaretListener text-comp
    (reify CaretListener (caretUpdate [this evt] (f)))))

(defn activate-caret-highlighter [text-comp]
  (add-caret-listener text-comp #(highlight-caret-enclosure text-comp)))

(defn activate-error-highlighter [text-comp]
  (add-text-change-listener
    text-comp 
    #(do (.. text-comp getHighlighter removeAllHighlights)
             (highlight-bad-brackets text-comp))))

;; paren closing (doesn't work)

(defn close-bracket [text-area] ;; doesn't work yet
  (let [text (.getText text-area)
        right-pos (.getCaretPosition text-area)
        left-pos (find-left-enclosing-bracket text right-pos)
        left-char (.charAt text left-pos)]
    (if-let [right-char (get {\( \), \{ \}, \[ \]} left-char)]
      (.insert text-area (str right-char) right-pos))))


;; find/replace

(defn find-all-in-string [s t]
  (when (pos? (.length t))
    (loop [positions [] p-start 0]
      (let [p (inc p-start)
            pnew (.indexOf s t p)]
        (if (pos? pnew)
          (recur (conj positions pnew) pnew)
          positions)))))

(defn highlight-found [text-comp posns length]
  (when (pos? length)
    (doall
      (map #(highlight text-comp % (+ % length) Color/YELLOW)
        posns))))

(defn next-item [cur-pos posns]
  (or (first (drop-while #(> cur-pos %) posns)) (first posns)))

(defn prev-item [cur-pos posns]
  (or (first (drop-while #(< cur-pos %) (reverse posns))) (last posns)))

(def search-highlights (atom nil))

(defn update-find-highlight [doc back]
  (let [sta (:search-text-area doc)
        dta (:doc-text-area doc)
        length (.. sta getText length)
        posns (find-all-in-string (.getText dta) (.getText sta))]
    (remove-highlights dta @search-highlights)
    (if (pos? (count posns))
      (let [selected-pos
             (if back (prev-item (dec (.getSelectionStart dta)) posns)
                      (next-item (.getSelectionStart dta) posns))
            posns (remove #(= selected-pos %) posns)]
        (when (pos? length)
          (reset! search-highlights
            (conj (highlight-found dta posns length)
                  (highlight dta selected-pos
                             (+ selected-pos length) (.getSelectionColor dta))))
          (set-selection dta selected-pos (+ selected-pos length))))
      (.setSelectionEnd dta (.getSelectionStart dta)))))

(defn start-find [doc]
  (let [sta (doc :search-text-area)]
    (doto sta
      (.setVisible true)
      (.requestFocus)
      (.selectAll))))

(defn stop-find [doc]
  (let [sta (doc :search-text-area)
        dta (doc :doc-text-area)]
    (.setVisible sta false)
    (remove-highlights dta @search-highlights)
    (reset! search-highlights nil)))

(defn escape-find [doc]
  (stop-find doc)
  (.requestFocus (:doc-text-area doc)))

(defn highlight-step [doc back]
  (let [dta (:doc-text-area doc)]
    (start-find doc)
    (if (not back)
        (.setSelectionStart dta (.getSelectionEnd dta)))
    (update-find-highlight doc back)))


;; projects tree

(declare restart-doc)

(defn get-project-root [path]
  (let [f (File. path)
        name (.getName f)]
    (if (and (or (= name "src")
                 (= name "lib"))
             (.isDirectory f))
      (File. (.getParent f))
      f)))

(defn get-code-files [dir suffix]
  (let [dir (File. dir)]
    (filter #(.endsWith (.getName %) suffix)
            (file-seq dir))))

(defn path-to-namespace [file-path]
  (let [drop-suffix #(clojure.contrib.string/butlast 4 %)]
    (-> file-path
        (.split (str "src" File/separator))
        second
        drop-suffix
        (.replace File/separator "."))))

(defn file-node [text file-path]
  (proxy [File] [file-path]
    (toString [] text)))

(defn add-node [parent node-str file-path]
  (let [node  (DefaultMutableTreeNode.
                (file-node node-str file-path))]
    (.add parent node)
    node))

(defn add-code-file-to-src-node [src-node code-file]
  (let [f (.getAbsolutePath code-file)
        namespace (path-to-namespace f)]
        (add-node src-node namespace f)))

(defn add-srcs-to-src-node [src-node src-dir]
  (doall (map #(add-code-file-to-src-node src-node %)
              (get-code-files src-dir ".clj"))))

(defn add-project-to-tree [doc project-root]
  (let [model (.getModel (doc :docs-tree))
        src-path (str project-root File/separator "src")]
    (-> model
      .getRoot
      (add-node (.getName (File. project-root)) project-root)
      (add-node "src" src-path)
      (add-srcs-to-src-node src-path))
    (.. model reload)))

(defn setup-tree [doc]
  (doto (:docs-tree doc)
    (.setModel (DefaultTreeModel. (DefaultMutableTreeNode. "projects")))
    (.setRootVisible false)
    (.addTreeSelectionListener
      (reify TreeSelectionListener
        (valueChanged [this e]
          (let [f (.. e getPath getLastPathComponent
                        getUserObject)]
            (when (.. f getName (endsWith ".clj"))
              (restart-doc doc f))))))))

;; build gui

(defn make-scroll-pane [text-area]
    (JScrollPane. text-area))

(defn put-constraint [comp1 edge1 comp2 edge2 dist]
  (let [edges {:n SpringLayout/NORTH
               :w SpringLayout/WEST
               :s SpringLayout/SOUTH
               :e SpringLayout/EAST}]
  (.. comp1 getParent getLayout
            (putConstraint (edges edge1) comp1 
                           dist (edges edge2) comp2))))

(defn put-constraints [comp & args]
  (let [args (partition 3 args)
        edges [:n :w :s :e]]
    (dorun (map #(apply put-constraint comp %1 %2) edges args))))

(defn constrain-to-parent [comp & args]
  (apply put-constraints comp
         (flatten (map #(cons (.getParent comp) %) (partition 2 args)))))

(defn add-line-numbers [text-comp max-lines]
  (let [row-height (.. text-comp getGraphics
                       (getFontMetrics (. text-comp getFont)) getHeight)
        sp (.. text-comp getParent getParent)
        jl (JList.
             (proxy [AbstractListModel] []
               (getSize [] max-lines)
               (getElementAt [i] (str (inc i) " "))))
        cr (. jl getCellRenderer)]
    (.setMargin text-comp (Insets. 0 10 0 0))
    (dorun (map #(.removeMouseListener jl %) (.getMouseListeners jl)))
    (dorun (map #(.removeMouseMotionListener jl %) (.getMouseMotionListeners jl)))
    (doto jl
      (.setBackground (Color. 235 235 235))
      (.setForeground (Color. 50 50 50))
      (.setFixedCellHeight row-height)
      (.setFont (Font. "Monaco" Font/PLAIN 8)))
    (doto cr
      (.setHorizontalAlignment JLabel/RIGHT)
      (.setVerticalAlignment JLabel/BOTTOM))
    (.setRowHeaderView sp jl)))

(defn make-undoable [text-area]
  (let [undoMgr (UndoManager.)]
    (.. text-area getDocument (addUndoableEditListener
        (reify UndoableEditListener
          (undoableEditHappened [this evt] (.addEdit undoMgr (.getEdit evt))))))
    (attach-action-keys text-area
      ["meta Z" #(if (.canUndo undoMgr) (.undo undoMgr))]
      ["meta shift Z" #(if (.canRedo undoMgr) (.redo undoMgr))])))

(defn auto-indent-str [text-comp offset]
  (let [bracket-pos (find-left-enclosing-bracket
                      (.getText text-comp) offset)]
    (if (pos? bracket-pos)
      (let [bracket (.. text-comp getText (charAt bracket-pos))
            col (:col (get-coords text-comp bracket-pos))
            indent-size (if (= bracket \() 2 1)] ;\) avoids highlighting problems
        (apply str "\n" (repeat (+ col indent-size) " ")))
      "\n")))
     
(defn set-tab-as-spaces [text-comp n]
  (let [tab-str (apply str (repeat n " "))]
    (.. text-comp getDocument
        (setDocumentFilter
          (proxy [DocumentFilter] []
            (replace [fb offset len text attrs]
              (.replace
                fb offset len
                (condp = text
                  "\t" tab-str
                  "\n" (auto-indent-str text-comp offset)
                  text)
                  attrs)))))))

(defn make-split-pane [comp1 comp2 horizontal resize-weight]
  (doto (JSplitPane. (if horizontal JSplitPane/HORIZONTAL_SPLIT 
                                    JSplitPane/VERTICAL_SPLIT)
                     true comp1 comp2)
        (.setResizeWeight resize-weight)
        (.setOneTouchExpandable false)
        (.setBorder (BorderFactory/createEmptyBorder))
        (.setDividerSize gap)))

(defn setup-search-text-area [doc]
  (let [sta (doto (doc :search-text-area)
      (.setVisible false)
      (.setFont (get-mono-font))
      (.setBorder (BorderFactory/createLineBorder Color/DARK_GRAY))
      (.addFocusListener (proxy [FocusAdapter] [] (focusLost [_] (stop-find doc)))))]
    (add-text-change-listener sta #(update-find-highlight doc false))
    (attach-action-keys sta ["ENTER" #(highlight-step doc false)]
                            ["shift ENTER" #(highlight-step doc true)]
                            ["ESCAPE" #(escape-find doc)])))

(defn create-doc []
  (let [doc-text-area (make-text-area)
        repl-out-text-area (make-text-area)
        repl-in-text-area (make-text-area)
        search-text-area (JTextField.)
        pos-label (JLabel.)
        f (JFrame.)
        cp (.getContentPane f)
        layout (SpringLayout.)
        docs-tree (JTree.)
        doc {:doc-text-area doc-text-area
             :repl-out-text-area repl-out-text-area
             :repl-in-text-area repl-in-text-area
             :frame f
             :docs-tree docs-tree
             :search-text-area search-text-area
             :pos-label pos-label :file (atom nil)
             :repl-writer (create-clojure-repl
                            (make-repl-writer repl-out-text-area))}
        doc-split-pane (make-split-pane
                         (make-scroll-pane docs-tree)
                         (make-scroll-pane doc-text-area) true 0)
        repl-split-pane (make-split-pane
                          (make-scroll-pane repl-out-text-area)
                          (make-scroll-pane repl-in-text-area) false 0.75)
        split-pane (make-split-pane doc-split-pane repl-split-pane true 0.5)]
    (doto f
      (.setBounds 25 50 950 700)
      (.setLayout layout)
      (.add split-pane)
      (.add search-text-area)
      (.add pos-label))
    (doto pos-label
      (.setFont (Font. "Courier" Font/PLAIN 13)))
    (constrain-to-parent split-pane :n gap :w gap :s -16 :e (- gap))
    (constrain-to-parent pos-label :s -16 :w 0 :s 0 :w 100)
    (constrain-to-parent search-text-area :s -16 :w 100 :s -1 :w 300)
    (.layoutContainer layout f)
    (setup-search-text-area doc)
    (doto doc-text-area
      (.addCaretListener
        (reify CaretListener
          (caretUpdate [this evt] (display-caret-position doc)))))
    (activate-caret-highlighter doc-text-area)
    (doto repl-out-text-area (.setLineWrap true) (.setEditable false))
    (make-undoable repl-in-text-area)
    (setup-tree doc)
    doc))

;; file handling

(defn choose-file [frame suffix load]
  (let [dialog
    (doto (FileDialog. frame "Open clojure file"
            (if load FileDialog/LOAD FileDialog/SAVE))
      (.setFilenameFilter
        (reify FilenameFilter
          (accept [this _ name] (. name endsWith suffix))))
      (.setVisible true))
    d (.getDirectory dialog)
    n (.getFile dialog)]
    (if (and d n)
      (File. d n))))

(defn restart-doc [doc ^File file]
  (let [frame (doc :frame)]
    (let [text-area (doc :doc-text-area)]
      (if file
        (do (.read text-area (FileReader. (.getAbsolutePath file)) nil)
            (.setTitle frame (.getPath file)))
        (do (.setText text-area "")
            (.setTitle frame "Untitled")))
      (make-undoable text-area)
      (set-tab-as-spaces text-area 2)
      (activate-error-highlighter text-area)
      (reset! (doc :file) file)
      (apply-namespace-to-repl doc))))

(defn open-file [doc suffix]
  (let [frame (doc :frame)]
    (when-let [file (choose-file frame suffix true)]
      (restart-doc doc file))))

(defn new-file [doc]
  (restart-doc doc nil))

(declare save-file-as)

(defn save-file [doc]
  (if-not @(doc :file)
    (save-file-as doc)
    (.write (doc :doc-text-area) (FileWriter. @(doc :file)))))

(defn save-file-as [doc]
  (let [frame (doc :frame)
        file (choose-file frame ".clj" false)]
    (reset! (doc :file) file)
    (if @(doc :file)
     (save-file doc)
     (.setTitle frame (.getPath file)))))

(defn open-project [doc]
  (println "not implemented."))

;; menu setup

(defn add-menu-item [menu item-name key-shortcut response-fn]
  (.add menu
    (doto (JMenuItem. item-name)
      (.setAccelerator (KeyStroke/getKeyStroke key-shortcut))
      (.addActionListener
        (reify ActionListener
          (actionPerformed [this action-event]
            (do (response-fn))))))))

(defn add-menu [menu-bar title & item-triples]
  "Each item-triple is a vector containing a
  menu item's text, shortcut key, and the function
  it executes."
  (let [menu (JMenu. title)]
    (doall (map #(apply add-menu-item menu %) item-triples))
    (.add menu-bar menu)))

(defn make-menus [doc]
  (System/setProperty "apple.laf.useScreenMenuBar" "true")
  (let [menu-bar (JMenuBar.)]
    (. (doc :frame) setJMenuBar menu-bar)
    (add-menu menu-bar "File"
      ["New" "meta N" #(new-file doc)]
      ["Open" "meta O" #(open-file doc ".clj")]
      ["Open project..." "meta shift O" #(open-project doc)]
      ["Save" "meta S" #(save-file doc)]
      ["Save as..." "meta R" #(save-file-as doc)])
    (add-menu menu-bar "Tools"
      ["Evaluate in REPL" "meta E" #(send-selected-to-repl doc)]
      ["Apply file ns to REPL" "meta L" #(apply-namespace-to-repl doc)]
      ["Find" "meta F" #(start-find doc)]
      ["Find next" "meta G" #(highlight-step doc false)]
      ["Find prev" "meta shift G" #(highlight-step doc true)]
      ["Clear REPL" "meta K" #(.setText (doc :repl-out-text-area) "")])))

;; startup

(def current-doc (atom nil))

(defn startup []
  (UIManager/setLookAndFeel (UIManager/getSystemLookAndFeelClassName))
  (let [doc (create-doc)]
     (reset! current-doc doc)
     (make-menus doc)
     (let [ta-in (doc :repl-in-text-area)
           ta-out (doc :repl-out-text-area)]
       (add-repl-input-handler doc))
     (.setVisible (doc :frame) true)
     (add-line-numbers (doc :doc-text-area) Short/MAX_VALUE)
     (add-project-to-tree doc "/projects/clooj/")))

(defn -show []
  (if (not @current-doc)
    (startup)
    (.setVisible (:frame current-doc) true)))

(defn -main [& args]
  (startup))

;; testing

(defn get-text []
  (.getText (current-doc :doc-text-area)))