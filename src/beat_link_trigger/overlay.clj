(ns beat-link-trigger.overlay
  "Serves a customizable overlay page for use with OBS Studio."
  (:require [clojure.java.browse :as browse]
            [clojure.java.io :as io]
            [compojure.route :as route]
            [compojure.core :as compojure]
            [ring.util.response :as response]
            [org.httpkit.server :as server]
            [selmer.parser :as parser]
            [beat-link-trigger.expressions :as expr]
            [beat-link-trigger.util :as util])
  (:import [org.deepsymmetry.beatlink DeviceFinder VirtualCdj Util
            DeviceAnnouncement DeviceUpdate Beat CdjStatus MixerStatus MediaDetails
            CdjStatus$TrackSourceSlot CdjStatus$TrackType]
           [org.deepsymmetry.beatlink.data TimeFinder MetadataFinder SignatureFinder
            PlaybackState TrackPositionUpdate SlotReference TrackMetadata AlbumArt ColorItem SearchableItem]))

(defn format-source-slot
  "Converts the Java enum value representing the slot from which a track
  was loaded to a nice human-readable string."
  [slot]
  (util/case-enum slot
    CdjStatus$TrackSourceSlot/NO_TRACK   "No Track"
    CdjStatus$TrackSourceSlot/CD_SLOT    "CD Slot"
    CdjStatus$TrackSourceSlot/SD_SLOT    "SD Slot"
    CdjStatus$TrackSourceSlot/USB_SLOT   "USB Slot"
    CdjStatus$TrackSourceSlot/COLLECTION "rekordbox"
    "Unknown Slot"))

(defn format-track-type
  "Converts the Java enum value representing the type of track that
  was loaded in a player to a nice human-readable string."
  [track]
  (util/case-enum track
    CdjStatus$TrackType/NO_TRACK         "No Track"
    CdjStatus$TrackType/CD_DIGITAL_AUDIO "CD Digital Audio"
    CdjStatus$TrackType/REKORDBOX        "Rekordbox"
    CdjStatus$TrackType/UNANALYZED       "Unanalyzed"
    "Unknown"))

(defn item-label
  "Given a searchable item, if it is not nil, returns its label."
  [^SearchableItem item]
  (when item (.-label item)))

(defn color-name
  "Given a non-nil color item, returns its name."
  [^ColorItem color]
  (when (and color (not (ColorItem/isNoColor (.color color))))
    (.-colorName color)))

(defn color-code
  "Given a non-nil color item, returns the CSS color code for it."
  [^ColorItem color]
  (when (and color (not (ColorItem/isNoColor (.color color))))
    (format "#%06x" (bit-and (.. color color getRGB) 0xffffff))))

(defn format-metadata
  "Builds a map describing the metadata of the track loaded in the
  specified player, if any, in a format convenient for use in the
  overlay template."
  [player]
  (when-let [metadata (.getLatestMetadataFor expr/metadata-finder player)]
    {:id              (.. metadata trackReference rekordboxId)
     :slot            (format-source-slot (.. metadata trackReference slot))
     :type            (format-track-type (.-trackType metadata))
     :title           (.getTitle metadata)
     :album           (item-label (.getAlbum metadata))
     :artist          (item-label (.getArtist metadata))
     :color-name      (color-name (.getColor metadata))
     :color           (color-code (.getColor metadata))
     :comment         (.getComment metadata)
     :added           (.getDateAdded metadata)
     :duration        (.getDuration metadata)
     :genre           (item-label (.getGenre metadata))
     :key             (item-label (.getKey metadata))
     :label           (item-label (.getLabel metadata))
     :original-artist (item-label (.getOriginalArtist metadata))
     :rating          (.getRating metadata)
     :remixer         (.getRemixer metadata)
     :year            (.getYear metadata)}))

(defn describe-device
  "Builds a template parameter map entry describing a device found on
  the network."
  [^DeviceAnnouncement device]
  (let [number (.getDeviceNumber device)]
    {number (merge {:number number
                    :name   (.getDeviceName device)
                    :address (.. device getAddress getHostAddress)}
                   (when-let [metadata (format-metadata number)]
                     {:track metadata}))}))

(defn- build-params
  "Sets up the overlay template parameters based on the current playback
  state."
  []
  {:players (apply merge (map describe-device (.getCurrentDevices expr/device-finder)))})

(defn- build-overlay
  "Builds a handler that renders the overlay template configured for
  the server being built."
  [config]
  (fn [_]
    (-> (parser/render-file (:template config) (build-params))
        response/response
        (response/content-type "text/html; charset=utf-8"))))

(defn- build-styles
  "Builds a handler that renders the stylesheet template configured for
  the server being built."
  [config]
  (fn [_]
    (-> (parser/render-file (:css config) {})
        response/response
        (response/content-type "text/css"))))

(defn- build-routes
  "Builds the set of routes that will handle requests for the server
  under construction."
  [config]
  (compojure/routes
   (compojure/GET "/" [] (build-overlay config))
   (compojure/GET "/styles.css" []  (build-styles config))
   (route/not-found "<p>Page not found.</p>")))

(defn- resolve-resource
  "Handles the optional resource overrides when starting the server. If
  one has been supplied, treat it as a file. Otherwise resolve
  `default-path` within our class path."
  [override default-path]
  (if override
    (.toURL (.toURI (io/file override)))
    default-path))

(defn start-server
  "Creates, starts, and returns an overlay server on the specified port.
  Optional keyword arguments allow you to supply a `:template` file
  that will be used to render the overlay and a `:css` file that will
  be served as `/styles.css` instead of the defaults. You can later
  shut down the server by pasing the value that was returned by this
  function to `stop-server`."
  [port & {:keys [template css show]}]
  (let [config {:port     port
                :template (resolve-resource template "beat_link_trigger/overlay.html")
                :css      (resolve-resource css "beat_link_trigger/styles.css")}
        server (server/run-server (build-routes config) {:port port})]
    (when show (browse/browse-url (str "http://127.0.0.1:" port "/")))
    (assoc config :stop server)))

(defn stop-server
  "Shuts down the supplied overlay web server (which must have been
  started by `start-server`)."
  [server]
  ((:stop server)))
