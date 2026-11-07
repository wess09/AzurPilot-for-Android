import com.azurpilot.ghio.remoteaccess.ParticleCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.random.Random

/**
 * 使用公开协议参考向量验证纠错、镜像旋转、曝光偏移及噪声拒绝。
 *
 * Checks correction, rotation, mirrors, exposure shifts, and noise rejection against a protocol reference vector.
 */
class ParticleCodeTest {
    private val vector = "100110010011001001001111100010111100000000000100000011110000000000000000001100001100011010110010100101110001011001001010100100111110100101001001011000110001011010011101100001100011110100010100100010010100101011110110111010101011000111000110111101110000111110111111011101011110011110100010010110101111010011110010011001001100"
    private val secret = "00112233445566778899aabbccddeeff"
    private fun frame(inverse: Boolean) = FloatArray(324) { if ((vector[it]=='1') xor inverse) 180f else 25f }

    @Test fun decodesReferenceFrameWithExposureAndRotation() {
        val a=frame(false); val b=frame(true).map { it+30f }.toFloatArray()
        assertEquals(secret,ParticleCode.decode(a,b))
        fun rotate(source: FloatArray) = FloatArray(324) { source[(17-it%18)*18+it/18] }
        fun mirror(source: FloatArray) = FloatArray(324) { source[it/18*18+17-it%18] }
        var left=a;var right=b
        repeat(4) {
            assertEquals(secret,ParticleCode.decode(left,right))
            assertEquals(secret,ParticleCode.decode(mirror(left),mirror(right)))
            left=rotate(left);right=rotate(right)
        }
    }
    @Test fun correctsSingleBitErrorsAndRejectsCorruptPackets() {
        val a=frame(false);val b=frame(true)
        val interior=(1..16).flatMap { y -> (1..16).map { x -> y*18+x } }
        for (index in listOf(4,20,38)) { val cell=interior[index];a[cell]=205-a[cell];b[cell]=205-b[cell] }
        assertEquals(secret,ParticleCode.decode(a,b))
        for (index in 50..60) { val cell=interior[index];a[cell]=205-a[cell];b[cell]=205-b[cell] }
        assertNull(ParticleCode.decode(a,b))
    }
    @Test fun rejectsUnrelatedFramesAndSamePhase() {
        assertNull(ParticleCode.decode(frame(false),frame(false)))
        val random=Random(17)
        repeat(200) { assertNull(ParticleCode.decode(FloatArray(324){random.nextFloat()*255},FloatArray(324){random.nextFloat()*255})) }
    }
}
