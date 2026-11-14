// The touch stick's throw (runtime/stick_curve.h): a radial deadzone, then a
// curve on what is left, with the direction of the push preserved exactly.
//
// It asserts rather than reporting, and includes nothing that needs the guest,
// the disc, a GPU or an Android device.
#include "stick_curve.h"

#include <cmath>
#include <cstdio>

namespace
{
    int g_checks = 0;
    int g_failures = 0;

    void Check(bool ok, const char* what)
    {
        g_checks++;
        if (ok) return;
        g_failures++;
        std::fprintf(stdout, "FAIL: %s\n", what);
    }

    bool Near(float a, float b, float tolerance = 1e-4f)
    {
        return std::fabs(a - b) <= tolerance;
    }

    float Magnitude(float x, float y) { return std::sqrt(x * x + y * y); }
}

int main()
{
    // ---- the deadzone -------------------------------------------------------

    {
        float x = 0.0f, y = 0.0f;
        Check(!stick::Apply(x, y, {}), "a centred stick is not deflected");
        Check(x == 0.0f && y == 0.0f, "and says so with an exact zero");
    }

    {
        stick::Shape shape{ 0.08f, 1.45f, 1.0f };
        float x = 0.05f, y = 0.05f;     // inside it
        Check(!stick::Apply(x, y, shape), "a resting thumb is inside the deadzone");
        Check(x == 0.0f && y == 0.0f, "and the residual is taken to zero, not merely small");
    }

    {
        stick::Shape shape{ 0.08f, 1.0f, 1.0f };
        float x = 0.15f, y = 0.0f;
        Check(stick::Apply(x, y, shape), "past the deadzone the stick moves");
        Check(x > 0.0f, "and it moves the way the thumb went");
        Check(Near(y, 0.0f), "without inventing a sideways component");
    }

    {
        // With no deadzone and no curve the stick is the thumb, which is what
        // the right stick defaults to.
        stick::Shape shape{ 0.0f, 1.0f, 1.0f };
        float x = 0.5f, y = 0.0f;
        stick::Apply(x, y, shape);
        Check(Near(x, 0.5f), "an unshaped stick reports the thumb's throw");
    }

    // ---- the curve ----------------------------------------------------------

    {
        stick::Shape shape{ 0.0f, 1.0f, 1.0f };
        float x = 1.0f, y = 0.0f;
        stick::Apply(x, y, shape);
        Check(Near(x, 1.0f) && Near(y, 0.0f), "the rim is unchanged by a plain shape");
    }

    {
        // A deadzone must not eat the top of the throw: a thumb at the rim is
        // still at full speed.
        stick::Shape shape{ 0.2f, 1.0f, 1.0f };
        float x = 1.0f, y = 0.0f;
        stick::Apply(x, y, shape);
        Check(Near(x, 1.0f), "the rim is full deflection whatever the deadzone");
    }

    {
        stick::Shape shape{ 0.08f, 1.45f, 1.0f };
        float walkX = 0.4f, walkY = 0.0f;
        stick::Apply(walkX, walkY, shape);
        const float plain = (0.4f - 0.08f) / (1.0f - 0.08f);
        Check(walkX < plain, "a curve above one is finer around the middle than linear");
    }

    {
        stick::Shape shape{ 0.08f, 0.7f, 1.0f };
        float x = 0.4f, y = 0.0f;
        stick::Apply(x, y, shape);
        const float plain = (0.4f - 0.08f) / (1.0f - 0.08f);
        Check(x > plain, "a curve below one reaches the edge sooner");
    }

    {
        stick::Shape shape{ 0.08f, 1.45f, 0.85f };
        float x = 1.0f, y = 0.0f;
        stick::Apply(x, y, shape);
        Check(Near(x, 0.85f), "range scales what the stick sends at the rim");
    }

    {
        // A thumb that drags past the socket is still one full deflection, and
        // the direction it went in is kept.
        stick::Shape shape{ 0.0f, 1.0f, 1.0f };
        float x = 3.0f, y = -3.0f;
        stick::Apply(x, y, shape);
        Check(Near(Magnitude(x, y), 1.0f), "a long drag saturates at one");
        Check(Near(x, -y), "and keeps its diagonal");
    }

    // ---- direction and symmetry --------------------------------------------

    {
        stick::Shape shape{ 0.08f, 1.45f, 1.0f };
        float x = 0.6f, y = 0.8f;
        const float ratioBefore = x / y;
        stick::Apply(x, y, shape);
        Check(Near(x / y, ratioBefore, 1e-3f), "a diagonal stays a diagonal");
    }

    {
        stick::Shape shape{ 0.08f, 1.45f, 1.0f };
        float ax = 0.3f, ay = 0.9f, bx = -0.3f, by = 0.9f;
        float cx = 0.3f, cy = -0.9f, dx = -0.3f, dy = -0.9f;
        stick::Apply(ax, ay, shape);
        stick::Apply(bx, by, shape);
        stick::Apply(cx, cy, shape);
        stick::Apply(dx, dy, shape);
        Check(Near(Magnitude(ax, ay), Magnitude(bx, by), 1e-3f)
           && Near(Magnitude(ax, ay), Magnitude(cx, cy), 1e-3f)
           && Near(Magnitude(ax, ay), Magnitude(dx, dy), 1e-3f),
              "all four quadrants respond the same");
    }

    // ---- the whole throw ----------------------------------------------------

    {
        stick::Shape shape{ 0.08f, 1.45f, 1.0f };
        float previous = -1.0f;
        bool monotonic = true;
        for (int i = 0; i <= 1000; ++i)
        {
            float x = float(i) / 1000.0f;
            float y = 0.0f;
            stick::Apply(x, y, shape);
            if (x + 1e-6f < previous) monotonic = false;
            previous = x;
        }
        Check(monotonic, "pushing further never sends less");
        Check(Near(previous, 1.0f), "and the end of the throw is full deflection");
    }

    // ---- what it refuses to be asked for ------------------------------------

    {
        const stick::Shape shape = stick::Sanitize({ 5.0f, 0.0f, -1.0f });
        Check(Near(shape.deadzone, 0.9f), "an absurd deadzone is pulled in");
        Check(Near(shape.exponent, 0.25f), "an absurd curve is pulled in");
        Check(Near(shape.range, 0.0f), "a negative range is pulled in");
    }

    {
        stick::Shape shape{ 0.0f, 1.0f, 0.0f };
        float x = 0.7f, y = 0.0f;
        Check(!stick::Apply(x, y, shape), "a zero range is no deflection at all");
        Check(x == 0.0f && y == 0.0f, "not a NaN, which would poison the pad");
    }

    {
        // The pad's own numbers must survive being handed back and forth.
        stick::Shape shape{ 0.08f, 1.45f, 1.0f };
        for (int i = 0; i <= 200; ++i)
        {
            const float angle = float(i) * 3.14159265f / 100.0f;
            float x = std::cos(angle), y = std::sin(angle);
            stick::Apply(x, y, shape);
            if (std::isnan(x) || std::isnan(y) || std::isinf(x) || std::isinf(y))
            {
                Check(false, "no angle produces a NaN");
                break;
            }
        }
        Check(true, "no angle produces a NaN");
    }

    std::printf("%d checks, %d failed\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}
